package com.dev.Tvivo.source.drive

import com.dev.Tvivo.source.SourceAccessToken
import java.net.InetAddress
import java.net.Inet6Address
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DriveSlice5bTest {
    @Test fun restMapsDriveFailuresAndIo() = runBlocking {
        suspend fun failure(status: Int, reason: String = "", headers: Map<String, String> = emptyMap()): DriveFailure {
            val body = """{"error":{"errors":[{"reason":"$reason"}]}}""".toByteArray()
            val store = DriveIndexStore(DriveHttpClient { DriveHttpResponse(status, headers, body) }, { SourceAccessToken("test") })
            return try { store.listChildren(null); fail("expected failure"); error("unreachable") } catch (e: DriveFailure) { e }
        }
        assertSame(DriveFailure.Unauthorized, failure(401))
        assertSame(DriveFailure.QuotaExceeded, failure(403, "storageQuotaExceeded"))
        assertSame(DriveFailure.Forbidden, failure(403, "insufficientPermissions"))
        assertEquals(7_000L, (failure(429, headers = mapOf("Retry-After" to "7")) as DriveFailure.RateLimited).retryAfterMillis)
        assertSame(DriveFailure.Transient, failure(503))
        val offline = DriveIndexStore(DriveHttpClient { throw java.io.IOException("offline") }, { SourceAccessToken("test") })
        try { offline.listChildren(null); fail("expected offline") } catch (e: DriveFailure) { assertSame(DriveFailure.Offline, e) }
    }

    @Test fun pendingBootstrapRelistsAndDoesNotReplaceExistingIndexes() = runBlocking {
        val fake = BootstrapStore().apply { createFolder(null, DriveBootstrap.ROOT_NAME) }
        val root = fake.records.values.single()
        val state = MutableBootstrapState(BootstrapState(root.id, pending = true, createdByApp = true))
        // An existing index is user content; bootstrap validates it and leaves its bytes untouched.
        val movies = fake.createFolder(root.id, "Movies")
        val existing = fake.writeIndex(movies.id, "index.json", "user bytes".toByteArray())
        try { DriveBootstrap(fake, state).run(); fail("invalid existing index should be rejected") }
        catch (_: IllegalStateException) { }
        assertArrayEquals("user bytes".toByteArray(), fake.contents[existing.id])
    }

    @Test fun createAcceptedThenResponseDroppedIsRecoveredByRelisting() = runBlocking {
        val fake = BootstrapStore()
        fake.dropNextCreateResponse = true
        val state = MutableBootstrapState()
        val ready = DriveBootstrap(fake, state).run() as BootstrapResult.Ready
        assertTrue(ready.seeded)
        assertEquals(1, fake.records.values.count { it.name == DriveBootstrap.ROOT_NAME })
        assertEquals(4, fake.records.values.count { it.mimeType == DriveIndexStore.JSON_MIME })
    }

    @Test fun indexAcceptedThenResponseDroppedIsReusedOnRetry() = runBlocking {
        val fake = BootstrapStore().apply { dropNextIndexResponse = true }
        val state = MutableBootstrapState()
        assertTrue(DriveBootstrap(fake, state).run() is BootstrapResult.Ready)
        assertEquals(8, fake.records.size)
        assertTrue(DriveBootstrap(fake, state).run() is BootstrapResult.Ready)
        assertEquals(8, fake.records.size)
        assertEquals(4, fake.records.values.count { it.mimeType == DriveIndexStore.JSON_MIME })
        assertEquals(1, fake.records.values.count { it.name == "library.json" })
    }

    @Test fun restUploadsIndexWithBytesInOneMultipartCreate() = runBlocking {
        val calls = mutableListOf<DriveHttpRequest>()
        val store = DriveIndexStore(DriveHttpClient { request ->
            calls += request
            when (request.method) {
                "GET" -> if (request.url.contains("/files/file-1?")) DriveHttpResponse(200,
                    body = """{"id":"file-1","name":"index.json","mimeType":"application/json","version":"1"}""".toByteArray())
                    else DriveHttpResponse(200, body = """{"files":[]}""".toByteArray())
                else -> DriveHttpResponse(200, body = """{"id":"file-1"}""".toByteArray())
            }
        }, { SourceAccessToken("test") })
        store.writeIndex("parent", "index.json", "{\"items\":[]}".toByteArray())
        val post = calls.single { it.method == "POST" }
        assertTrue(post.url.contains("/upload/drive/v3/files?uploadType=multipart"))
        assertTrue(post.headers["Content-Type"]!!.startsWith("multipart/related; boundary="))
        assertTrue(post.body!!.toString(Charsets.UTF_8).contains("{\"items\":[]}"))
        assertFalse(calls.any { it.method == "PATCH" })
    }

    @Test fun retryAfterIsHonoredAndPermissionDoesNotRetry() = runBlocking {
        val fake = BootstrapStore().apply { seedForSync() }
        val waits = mutableListOf<Long>()
        val syncedAt = mutableListOf<Long>()
        fake.failReads = ArrayDeque(listOf(DriveFailure.RateLimited(9_000), DriveFailure.Transient))
        val engine = syncEngine(fake, sleeper = { waits += it }, jitter = { 0 }, syncedTimes = syncedAt)
        val result = engine.sync("root")
        assertTrue(result is SyncResult.Success)
        assertEquals(listOf(9_000L, 500L), waits)
        assertEquals(listOf(42L, 42L, 42L), syncedAt)

        val denied = BootstrapStore().apply { seedForSync(); failReads = ArrayDeque(listOf(DriveFailure.Forbidden)) }
        val permissionWaits = mutableListOf<Long>()
        assertTrue(syncEngine(denied, sleeper = { permissionWaits += it }).sync("root") is SyncResult.Failure)
        assertTrue(permissionWaits.isEmpty())
    }

    @Test fun syncCancellationAndSameAccountSingleFlight() = runBlocking {
        val fake = BootstrapStore().apply { seedForSync() }
        var active = 0; var maxActive = 0
        fake.beforeRead = { active++; maxActive = maxOf(maxActive, active); delay(15); active-- }
        val engine1 = syncEngine(fake); val engine2 = syncEngine(fake)
        val a = async { engine1.sync("root") }; val b = async { engine2.sync("root") }
        a.await(); b.await()
        assertEquals(1, maxActive)
        val cancellationStore = BootstrapStore().apply { seedForSync(); beforeRead = { throw CancellationException("cancel") } }
        val cancellationEngine = syncEngine(cancellationStore, accountId = "slice5b-cancel")
        assertTrue(runCatching { cancellationEngine.sync("root") }.exceptionOrNull() is CancellationException)
    }

    @Test fun guardedRedirectPinsCheckedAddressAndRejectsRebinding() {
        val public = InetAddress.getByName("93.184.216.34")
        var lookups = 0
        val guard = UrlGuard(AddressResolver { lookups++; if (lookups == 1) listOf(public) else listOf(InetAddress.getByName("10.0.0.8")) })
        var connected: InetAddress? = null
        val client = GuardedUrlClient(guard, PinnedConnection { checked ->
            connected = checked.address
            GuardedResponse(302, "https://same.test/next")
        })
        assertThrows(IllegalArgumentException::class.java) { client.get("https://same.test/start") }
        assertEquals(2, lookups)
        assertEquals(public, connected)
    }

    @Test fun redirectCountIsBounded() {
        val guard = UrlGuard(AddressResolver { listOf(InetAddress.getByName("93.184.216.34")) })
        var count = 0
        val client = GuardedUrlClient(guard, PinnedConnection { count++; GuardedResponse(302, "/again") }, maxRedirects = 2)
        assertThrows(IllegalStateException::class.java) { client.get("https://safe.test/start") }
        assertEquals(3, count)
    }

    @Test fun urlGuardAllowsOnlyPublicIpv4AndRejectsEverySpecialPurposeBlock() {
        val rejected = listOf(
            "0.1.2.3", "10.1.2.3", "100.64.0.1", "100.127.255.254", "127.0.0.1",
            "169.254.1.1", "172.16.0.1", "172.31.255.254", "192.0.0.1", "192.0.2.1", "192.31.196.1",
            "192.88.99.1", "192.168.1.1", "198.18.0.1", "198.19.255.254", "198.51.100.1",
            "203.0.113.1", "224.0.0.1", "240.0.0.1", "255.255.255.255"
        )
        rejected.forEach { ip -> assertRejectedAddress(ip) }
        listOf("1.1.1.1", "8.8.8.8", "93.184.216.34", "192.0.1.1", "198.20.0.1", "223.255.255.254")
            .forEach { ip -> assertAcceptedAddress(ip) }
    }

    @Test fun urlGuardAllowsOnlyGlobalUnicastIpv6AndRechecksEmbeddedIpv4() {
        listOf(
            "::", "::1", "::8.8.8.8", "fc00::1", "fdff::1", "fe80::1",
            "ff02::1", "2001::1", "2001:db8::1", "64:ff9b::808:808", "64:ff9b:1::808:808",
            "2002:0a00:1::1", "2001:0000:4136:e378:8000:63bf:3fff:fdd2"
        ).forEach { ip -> assertRejectedAddress(ip) }
        assertRejectedInet6(ByteArray(16).also { it[10] = 0xff.toByte(); it[11] = 0xff.toByte(); it[12] = 8; it[13] = 8; it[14] = 8; it[15] = 8 })
        listOf("2001:4860:4860::8888", "2606:4700:4700::1111", "2a00:1450:4001::1", "3fff::1", "2002:0808:0808::1")
            .forEach { ip -> assertAcceptedAddress(ip) }
    }

    @Test fun mockHttpStillRequiresItsExplicitFlagAndPublicResolvedAddress() {
        val resolver = AddressResolver { listOf(InetAddress.getByName("93.184.216.34")) }
        assertThrows(IllegalArgumentException::class.java) { UrlGuard(resolver).resolve("http://mock.test/file") }
        assertEquals("mock.test", UrlGuard(resolver, allowMockHttp = true).resolve("http://mock.test/file").uri.host)
        assertThrows(IllegalArgumentException::class.java) {
            UrlGuard(AddressResolver { listOf(InetAddress.getByName("127.0.0.1")) }, allowMockHttp = true).resolve("http://mock.test/file")
        }
    }

    private fun assertRejectedAddress(ip: String) {
        assertThrows("$ip must be rejected", IllegalArgumentException::class.java) {
            UrlGuard(AddressResolver { listOf(InetAddress.getByName(ip)) }).resolve("https://address.test/")
        }
    }

    private fun assertAcceptedAddress(ip: String) {
        assertEquals(InetAddress.getByName(ip), UrlGuard(AddressResolver { listOf(InetAddress.getByName(ip)) }).resolve("https://address.test/").address)
    }

    private fun assertRejectedInet6(bytes: ByteArray) {
        val address = Inet6Address.getByAddress(null, bytes, -1)
        assertThrows(IllegalArgumentException::class.java) {
            UrlGuard(AddressResolver { listOf(address) }).resolve("https://address.test/")
        }
    }

    private fun syncEngine(store: BootstrapStore, sleeper: suspend (Long) -> Unit = {}, jitter: (Long) -> Long = { 0 }, accountId: String = "slice5b-test-account", syncedTimes: MutableList<Long> = mutableListOf()) = DriveSyncEngine(
        accountId, store, object : SyncStateStore {
            private val values = mutableMapOf<String, IndexFingerprint>()
            override suspend fun fingerprint(kind: String) = values[kind]
            override suspend fun saveFingerprint(kind: String, value: IndexFingerprint) { values[kind] = value }
        }, object : DriveIndexApplier {
            override suspend fun movies(value: com.dev.Tvivo.source.library.ParseResult<com.dev.Tvivo.source.library.ParsedList<com.dev.Tvivo.source.library.MovieEntry>>, syncedAt: Long) { syncedTimes += syncedAt }
            override suspend fun series(value: com.dev.Tvivo.source.library.ParseResult<com.dev.Tvivo.source.library.ParsedList<com.dev.Tvivo.source.library.ShowEntry>>, syncedAt: Long) { syncedTimes += syncedAt }
            override suspend fun music(value: com.dev.Tvivo.source.library.ParseResult<com.dev.Tvivo.source.library.ParsedList<com.dev.Tvivo.source.library.SongEntry>>, syncedAt: Long) { syncedTimes += syncedAt }
        }, clock = DriveClock { 42L }, jitter = jitter, sleeper = sleeper)

    private class MutableBootstrapState(var state: BootstrapState = BootstrapState()) : BootstrapStateStore {
        override suspend fun load() = state
        override suspend fun save(state: BootstrapState) { this.state = state }
    }

    private class BootstrapStore : com.dev.Tvivo.source.IndexStore {
        val records = linkedMapOf<String, com.dev.Tvivo.source.IndexMetadata>()
        val parents = mutableMapOf<String, String?>(); val contents = mutableMapOf<String, ByteArray>()
        var id = 0; var dropNextCreateResponse = false; var dropNextIndexResponse = false
        var failReads: ArrayDeque<DriveFailure> = ArrayDeque()
        var beforeRead: suspend () -> Unit = {}
        override suspend fun listChildren(parentId: String?, pageToken: String?) = com.dev.Tvivo.source.IndexPage(records.values.filter { parents[it.id] == parentId }, null)
        override suspend fun createFolder(parentId: String?, name: String): com.dev.Tvivo.source.IndexMetadata {
            val item = add(parentId, name, DriveIndexStore.FOLDER_MIME, null)
            if (dropNextCreateResponse) { dropNextCreateResponse = false; throw DriveFailure.Offline }
            return item
        }
        override suspend fun writeIndex(parentId: String, name: String, bytes: ByteArray): com.dev.Tvivo.source.IndexMetadata {
            val item = add(parentId, name, DriveIndexStore.JSON_MIME, bytes)
            if (dropNextIndexResponse) { dropNextIndexResponse = false; throw DriveFailure.Offline }
            return item
        }
        override suspend fun readIndex(fileId: String): ByteArray { beforeRead(); if (failReads.isNotEmpty()) throw failReads.removeFirst(); return contents.getValue(fileId) }
        override suspend fun metadata(fileId: String) = records.getValue(fileId)
        fun seedForSync() {
            add("root", "Movies", DriveIndexStore.FOLDER_MIME, null).also { add(it.id, "index.json", DriveIndexStore.JSON_MIME, emptyIndex("movies")) }
            add("root", "Series", DriveIndexStore.FOLDER_MIME, null).also { add(it.id, "index.json", DriveIndexStore.JSON_MIME, emptyIndex("series")) }
            add("root", "Music", DriveIndexStore.FOLDER_MIME, null).also { add(it.id, "index.json", DriveIndexStore.JSON_MIME, emptyIndex("music")) }
        }
        private fun emptyIndex(kind: String) = """{"format":"tvivo.link-library","version":1,"kind":"$kind","items":[]}""".toByteArray()
        private fun add(parent: String?, name: String, mime: String, bytes: ByteArray?): com.dev.Tvivo.source.IndexMetadata {
            val item = com.dev.Tvivo.source.IndexMetadata("id-${++id}", name, mime, bytes?.size?.toLong(), 1, "1", "md5")
            records[item.id] = item; parents[item.id] = parent; if (bytes != null) contents[item.id] = bytes; return item
        }
    }
}
