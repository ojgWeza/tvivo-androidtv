package com.dev.Tvivo.source.drive

import com.dev.Tvivo.source.IndexMetadata
import com.dev.Tvivo.source.IndexPage
import com.dev.Tvivo.source.IndexStore
import com.dev.Tvivo.source.library.*
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DriveSlice5Test {
    @Test fun bootstrapCreatesSeedAndThenDoesNotReseed() = runBlocking {
        val fake = FakeStore(); val state = MemoryBootstrapState()
        val first = DriveBootstrap(fake, state).run() as BootstrapResult.Ready
        assertTrue(first.seeded)
        val second = DriveBootstrap(fake, state).run() as BootstrapResult.Ready
        assertFalse(second.seeded)
        assertEquals(4, fake.jsonFiles.size)
    }

    @Test fun twoRootsRequiresChoice() = runBlocking {
        val fake = FakeStore().apply { createFolder(null, "Tvivo Library"); createFolder(null, "Tvivo Library") }
        val result = DriveBootstrap(fake, MemoryBootstrapState()).run()
        assertTrue(result is BootstrapResult.NeedsChoice)
    }

    @Test fun syncAppliesValidEmptyListAndRejectsCorruptWithoutApply() = runBlocking {
        val fake = FakeStore(); val state = MemoryBootstrapState(); val root = (DriveBootstrap(fake, state).run() as BootstrapResult.Ready).rootId
        val sink = RecordingApplier(); val fingerprints = MemoryFingerprints()
        val initial = DriveSyncEngine("acct", fake, fingerprints, sink).sync(root)
        assertTrue(initial is SyncResult.Success)
        val movieFolder = fake.listAll(root).first { it.name == "Movies" }.id
        val file = fake.listAll(movieFolder).single { it.name == "index.json" }
        fake.contents[file.id] = """{"format":"tvivo.link-library","version":1,"kind":"movies","items":[]}""".toByteArray()
        fake.touch(file.id)
        val empty = DriveSyncEngine("acct", fake, fingerprints, sink).sync(root)
        assertTrue(empty is SyncResult.Success)
        assertEquals(0, sink.movieCounts.last())
        fake.contents[file.id] = "not json".toByteArray(); fake.touch(file.id)
        val before = sink.movieCounts.size
        assertTrue(DriveSyncEngine("acct", fake, fingerprints, sink).sync(root) is SyncResult.Failure)
        assertEquals(before, sink.movieCounts.size)
    }

    @Test fun urlGuardRejectsPrivateAndReturnsResolvedPublicAddress() {
        val guard = UrlGuard(AddressResolver { host -> listOf(InetAddress.getByName(if (host == "private.test") "10.2.3.4" else "93.184.216.34")) })
        assertEquals("93.184.216.34", guard.resolve("https://public.test/a").address.hostAddress)
        assertThrows(IllegalArgumentException::class.java) { guard.resolve("https://private.test/a") }
        assertThrows(IllegalArgumentException::class.java) { guard.resolve("file:///etc/passwd") }
    }

    private class MemoryBootstrapState : BootstrapStateStore {
        var current = BootstrapState()
        override suspend fun load() = current
        override suspend fun save(state: BootstrapState) { current = state }
    }
    private class MemoryFingerprints : SyncStateStore {
        val values = mutableMapOf<String, IndexFingerprint>()
        override suspend fun fingerprint(kind: String) = values[kind]
        override suspend fun saveFingerprint(kind: String, value: IndexFingerprint) { values[kind] = value }
    }
    private class RecordingApplier : DriveIndexApplier {
        val movieCounts = mutableListOf<Int>()
        override suspend fun movies(value: ParseResult<ParsedList<MovieEntry>>, syncedAt: Long) { movieCounts += (value as ParseResult.Valid).items.items.size }
        override suspend fun series(value: ParseResult<ParsedList<ShowEntry>>, syncedAt: Long) = Unit
        override suspend fun music(value: ParseResult<ParsedList<SongEntry>>, syncedAt: Long) = Unit
    }
    private class FakeStore : IndexStore {
        private val records = linkedMapOf<String, IndexMetadata>()
        val contents = mutableMapOf<String, ByteArray>()
        val jsonFiles get() = records.values.filter { it.mimeType == DriveIndexStore.JSON_MIME }
        private var id = 0
        private var version = 0L
        override suspend fun listChildren(parentId: String?, pageToken: String?): IndexPage = IndexPage(records.values.filter { parent(it.id) == parentId }, null)
        private val parents = mutableMapOf<String, String?>()
        private fun parent(id: String) = parents[id]
        suspend fun listAll(parent: String) = listChildren(parent).files
        override suspend fun createFolder(parentId: String?, name: String) = add(parentId, name, DriveIndexStore.FOLDER_MIME, null)
        override suspend fun writeIndex(parentId: String, name: String, bytes: ByteArray): IndexMetadata = add(parentId, name, DriveIndexStore.JSON_MIME, bytes)
        override suspend fun readIndex(fileId: String) = contents[fileId] ?: error("missing")
        override suspend fun metadata(fileId: String) = records.getValue(fileId)
        fun touch(fileId: String) { version++; records[fileId] = records.getValue(fileId).copy(version = version.toString()) }
        private fun add(parentId: String?, name: String, mime: String, data: ByteArray?): IndexMetadata {
            val key = "id-${++id}"; version++
            val item = IndexMetadata(key, name, mime, data?.size?.toLong(), version, version.toString(), "md5-$version")
            records[key] = item; parents[key] = parentId; data?.let { contents[key] = it }; return item
        }
    }
}
