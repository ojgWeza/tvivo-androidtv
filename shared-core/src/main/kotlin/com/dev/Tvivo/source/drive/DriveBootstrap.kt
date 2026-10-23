package com.dev.Tvivo.source.drive

import com.dev.Tvivo.source.IndexMetadata
import com.dev.Tvivo.source.IndexStore
import com.dev.Tvivo.source.library.*
import com.google.gson.Gson
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class BootstrapState(val rootId: String? = null, val pending: Boolean = false, val createdByApp: Boolean = false)
interface BootstrapStateStore { suspend fun load(): BootstrapState; suspend fun save(state: BootstrapState) }
sealed interface BootstrapResult {
    data class Ready(val rootId: String, val seeded: Boolean) : BootstrapResult
    data class NeedsChoice(val rootIds: List<String>) : BootstrapResult
}

enum class BootstrapStep { FINDING_LIBRARY, CREATING_FOLDERS, ADDING_SAMPLE_LINKS, OPENING_SAVED_LIBRARY }

class DriveBootstrap(
    private val store: IndexStore,
    private val state: BootstrapStateStore,
    private val onStep: (BootstrapStep) -> Unit = {},
    private val seedContent: suspend () -> SeedContent = { Seed.buildSeedContent() },
    private val allowMockHttp: Boolean = false
) {
    private val gson = Gson()
    suspend fun run(): BootstrapResult {
        onStep(BootstrapStep.FINDING_LIBRARY)
        val saved = state.load()
        val roots = all(null).filter { it.name == ROOT_NAME && it.mimeType == DriveIndexStore.FOLDER_MIME }
        val selected = saved.rootId?.let { id -> roots.firstOrNull { it.id == id } }
        val rootWasCreated = saved.pending && saved.createdByApp
        val root = selected ?: when (roots.size) {
            0 -> {
                state.save(BootstrapState(null, pending = true, createdByApp = true))
                createOrFind(null, ROOT_NAME, DriveIndexStore.FOLDER_MIME).also { state.save(BootstrapState(it.id, pending = true, createdByApp = true)) }
            }
            1 -> roots.single().also { if (rootWasCreated) state.save(BootstrapState(it.id, pending = true, createdByApp = true)) }
            else -> return BootstrapResult.NeedsChoice(roots.map { it.id })
        }
        val pendingState = state.load()
        val pending = pendingState.rootId == root.id && pendingState.pending
        if (!pending) {
            onStep(BootstrapStep.OPENING_SAVED_LIBRARY)
            state.save(pendingState.copy(rootId = root.id))
            return BootstrapResult.Ready(root.id, false)
        }

        onStep(BootstrapStep.CREATING_FOLDERS)
        val children = mutableMapOf<String, String>()
        for (name in CHILD_FOLDERS) children[name] = getOrCreate(root.id, name, DriveIndexStore.FOLDER_MIME).id
        val seeded = pendingState.createdByApp
        onStep(BootstrapStep.ADDING_SAMPLE_LINKS)
        val seed = seedContent()
        val indexes = listOf(
            Triple("Movies", "index.json", seed.movies), Triple("Series", "index.json", seed.series), Triple("Music", "index.json", seed.songs),
        )
        for ((folder, file, content) in indexes) {
            val found = findChild(children.getValue(folder), file, DriveIndexStore.JSON_MIME)
            if (found == null) {
                if (!seeded) throw IllegalStateException("Library file missing: $folder/$file")
                val payload = encode(content)
                store.writeIndex(children.getValue(folder), file, payload)
            } else validateIndex(folder, store.readIndex(found.id))
        }
        val marker = findChild(root.id, "library.json", DriveIndexStore.JSON_MIME)
        if (marker == null) store.writeIndex(root.id, "library.json", """{"format":"tvivo.link-library","version":1,"bootstrap":"complete"}""".toByteArray())
        else if (parseLibraryMarker(store.readIndex(marker.id).toString(Charsets.UTF_8)) !is ParseResult.Valid) throw IllegalStateException("Invalid library marker")
        onStep(BootstrapStep.OPENING_SAVED_LIBRARY)
        state.save(BootstrapState(root.id, pending = false, createdByApp = false))
        return BootstrapResult.Ready(root.id, seeded)
    }
    private suspend fun all(parent: String?): List<IndexMetadata> {
        val result = mutableListOf<IndexMetadata>(); var page: String? = null
        do { val next = store.listChildren(parent, page); result += next.files; page = next.nextPageToken } while (page != null)
        return result
    }
    private suspend fun findChild(parent: String, name: String, mime: String) = all(parent).firstOrNull { it.name == name && it.mimeType == mime }
    private suspend fun getOrCreate(parent: String, name: String, mime: String): IndexMetadata {
        findChild(parent, name, mime)?.let { return it }
        // Re-list immediately before create to recover create-after-timeout safely.
        findChild(parent, name, mime)?.let { return it }
        return createOrFind(parent, name, mime)
    }
    private suspend fun createOrFind(parent: String?, name: String, mime: String): IndexMetadata {
        try { return store.createFolder(parent, name) }
        catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            val created = all(parent).firstOrNull { it.name == name && it.mimeType == mime }
            if (created != null) return created
            throw failure
        }
    }
    private fun validateIndex(folder: String, bytes: ByteArray) {
        val json = bytes.toString(Charsets.UTF_8)
        val valid = when (folder) { "Movies" -> parseMovies(json, allowMockHttp); "Series" -> parseSeries(json, allowMockHttp); else -> parseMusic(json, allowMockHttp) }
        if (valid !is ParseResult.Valid) throw IllegalStateException("Invalid existing index $folder/index.json")
    }
    private fun encode(value: Any): ByteArray {
        val kind = when (value) { is List<*> -> when (value.firstOrNull()) { is MovieEntry -> "movies"; is ShowEntry -> "series"; else -> "music" }; else -> error("Unsupported seed list") }
        val items = gson.toJsonTree(value)
        return gson.toJson(com.google.gson.JsonObject().apply {
            addProperty("format", "tvivo.link-library"); addProperty("version", 1); addProperty("kind", kind); add("items", items)
        }).toByteArray(Charsets.UTF_8)
    }
    companion object {
        const val ROOT_NAME = "Tvivo Library"
        val CHILD_FOLDERS = listOf("Movies", "Series", "Music")
    }
}

/** Prevent duplicate bootstrap calls for the same local account. */
class BootstrapCoordinator {
    private val locks = ConcurrentHashMap<String, Mutex>()
    suspend fun run(accountId: String, bootstrap: DriveBootstrap) = locks.getOrPut(accountId) { Mutex() }.withLock { bootstrap.run() }
}
