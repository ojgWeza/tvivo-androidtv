package com.dev.Tvivo.source.drive

import com.dev.Tvivo.source.IndexMetadata
import com.dev.Tvivo.source.IndexStore
import com.dev.Tvivo.source.library.*
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class IndexFingerprint(val id: String, val version: String?, val md5: String?, val modifiedAtMillis: Long?)
interface SyncStateStore {
    suspend fun fingerprint(kind: String): IndexFingerprint?
    suspend fun saveFingerprint(kind: String, value: IndexFingerprint)
}
interface DriveIndexApplier {
    suspend fun movies(value: ParseResult<ParsedList<MovieEntry>>, syncedAt: Long)
    suspend fun series(value: ParseResult<ParsedList<ShowEntry>>, syncedAt: Long)
    suspend fun music(value: ParseResult<ParsedList<SongEntry>>, syncedAt: Long)
}
fun interface DriveClock { fun nowMillis(): Long }
fun interface AddressResolver { fun resolve(host: String): List<InetAddress> }

/** Resolves and validates every hop; callers must connect to the returned address and repeat on redirects. */
class UrlGuard(private val resolver: AddressResolver = AddressResolver { InetAddress.getAllByName(it).toList() }, private val allowMockHttp: Boolean = false) {
    fun resolve(url: String): CheckedUrl {
        val uri = URI(url)
        val host = uri.host?.lowercase()?.removeSuffix(".") ?: throw IllegalArgumentException("URL host required")
        require(uri.scheme.equals("https", true) || allowMockHttp && uri.scheme.equals("http", true))
        require(!host.equals("localhost") && !host.endsWith(".localhost"))
        val addresses = resolver.resolve(host)
        require(addresses.isNotEmpty() && addresses.all(::isPublic)) { "Private or special address rejected" }
        return CheckedUrl(uri, addresses.first())
    }
    private fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address.map { it.toInt() and 255 }
        if (b.size == 4) {
            val a = b[0]; val second = b[1]
            return a != 0 && a != 10 && a != 127 && !(a == 169 && second == 254) && !(a == 172 && second in 16..31) &&
                !(a == 192 && second == 168) && !(a == 100 && second in 64..127) && a < 224
        }
        if (b.size != 16) return false
        val uniqueLocal = b[0] and 0xfe == 0xfc
        val mapped = b.take(10).all { it == 0 } && b[10] == 255 && b[11] == 255
        val nat64 = b[0] == 0 && b[1] == 0x64 && b[2] == 0xff && b[3] == 0x9b
        val sixToFour = b[0] == 0x20 && b[1] == 0x02
        val embedded = when { mapped -> b.takeLast(4); nat64 -> b.takeLast(4); sixToFour -> b.subList(2, 6); else -> null }
        return !uniqueLocal && (embedded == null || isPublic(InetAddress.getByAddress(embedded.map(Int::toByte).toByteArray())))
    }
}
data class CheckedUrl(val uri: URI, val address: InetAddress)

/** A transport must connect to [CheckedUrl.address], never resolve [CheckedUrl.uri.host] itself. */
fun interface PinnedConnection { fun execute(checked: CheckedUrl): GuardedResponse }
data class GuardedResponse(val status: Int, val location: String? = null, val body: ByteArray = byteArrayOf())

/** Follows only explicit redirects, re-validating and pinning each destination. */
class GuardedUrlClient(private val guard: UrlGuard, private val connection: PinnedConnection, private val maxRedirects: Int = 5) {
    init { require(maxRedirects >= 0) }
    fun get(url: String): GuardedResponse {
        var current = URI(url)
        repeat(maxRedirects + 1) { hop ->
            val response = connection.execute(guard.resolve(current.toString()))
            if (response.status !in 300..399) return response
            val location = response.location ?: throw IllegalStateException("Redirect missing Location")
            if (hop == maxRedirects) throw IllegalStateException("Too many redirects")
            current = current.resolve(location)
        }
        error("unreachable")
    }
}

sealed interface SyncResult {
    data class Success(val changed: Set<String>) : SyncResult
    data class Failure(val copyKey: String, val cause: Throwable) : SyncResult
}

class DriveSyncEngine(
    private val accountId: String, private val store: IndexStore, private val state: SyncStateStore,
    private val applier: DriveIndexApplier, private val clock: DriveClock = DriveClock { System.currentTimeMillis() },
    private val jitter: (Long) -> Long = { bound -> Random.nextLong(bound.coerceAtLeast(1)) },
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val allowMockHttp: Boolean = false,
) {
    private val lock = locks.getOrPut(accountId) { Mutex() }
    suspend fun sync(rootId: String): SyncResult = lock.withLock {
        val changed = linkedSetOf<String>()
        try {
            val folders = listAll(rootId).associateBy { it.name }
            for ((kind, folder) in listOf("movies" to "Movies", "series" to "Series", "music" to "Music")) {
                val dir = folders[folder] ?: throw IllegalStateException("Library file missing")
                val file = listAll(dir.id).firstOrNull { it.name == "index.json" && it.mimeType == DriveIndexStore.JSON_MIME }
                    ?: throw IllegalStateException("Library file missing")
                val old = state.fingerprint(kind)
                if (old != null && same(old, file)) continue
                val bytes = withBackoff { store.readIndex(file.id) }
                if (bytes.size > 256 * 1024) throw IllegalStateException("Invalid library JSON")
                val text = bytes.toString(Charsets.UTF_8)
                val parsed = when (kind) { "movies" -> parseMovies(text, allowMockHttp); "series" -> parseSeries(text, allowMockHttp); else -> parseMusic(text, allowMockHttp) }
                if (parsed !is ParseResult.Valid) throw IllegalStateException("Invalid library JSON")
                val now = clock.nowMillis()
                when (kind) {
                    "movies" -> @Suppress("UNCHECKED_CAST") applier.movies(parsed as ParseResult<ParsedList<MovieEntry>>, now)
                    "series" -> @Suppress("UNCHECKED_CAST") applier.series(parsed as ParseResult<ParsedList<ShowEntry>>, now)
                    else -> @Suppress("UNCHECKED_CAST") applier.music(parsed as ParseResult<ParsedList<SongEntry>>, now)
                }
                state.saveFingerprint(kind, IndexFingerprint(file.id, file.version, file.md5Checksum, file.modifiedAtMillis))
                changed += kind
            }
            SyncResult.Success(changed)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) { SyncResult.Failure(copyKey(failure), failure) }
    }
    private suspend fun listAll(parent: String): List<IndexMetadata> {
        val all = mutableListOf<IndexMetadata>(); var page: String? = null
        do { val next = store.listChildren(parent, page); all += next.files; page = next.nextPageToken } while (page != null)
        return all
    }
    private fun same(old: IndexFingerprint, now: IndexMetadata): Boolean = when {
        old.id != now.id -> false
        old.version != null && now.version != null -> old.version == now.version
        old.md5 != null && now.md5Checksum != null -> old.md5.equals(now.md5Checksum, true)
        else -> old.modifiedAtMillis != null && now.modifiedAtMillis != null && old.modifiedAtMillis == now.modifiedAtMillis
    }
    private suspend fun <T> withBackoff(block: suspend () -> T): T {
        var attempt = 0
        while (true) try { return block() } catch (e: DriveFailure.RateLimited) {
            val base = (500L shl attempt.coerceAtMost(6)).coerceAtMost(30_000)
            sleeper(e.retryAfterMillis ?: (base / 2 + jitter(base)))
            attempt++
        } catch (e: DriveFailure.Transient) {
            if (attempt >= 5) throw e
            val base = (500L shl attempt.coerceAtMost(6)).coerceAtMost(30_000)
            sleeper(base / 2 + jitter(base)); attempt++
        }
    }
    private fun copyKey(error: Throwable) = when (error) {
        DriveFailure.Unauthorized -> "drive.auth.expired"
        DriveFailure.QuotaExceeded -> "drive.quota.exceeded"
        DriveFailure.Forbidden -> "drive.permission.denied"
        is DriveFailure.RateLimited -> "drive.rate_limited"
        DriveFailure.Offline -> "drive.offline"
        DriveFailure.Transient -> "drive.refresh.failed"
        else -> if (error.message?.contains("JSON") == true) "drive.index.repair" else "drive.refresh.failed"
    }
    companion object { private val locks = ConcurrentHashMap<String, Mutex>() }
}
