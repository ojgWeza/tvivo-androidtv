package com.dev.tvivo.desktop.catalog

import com.dev.Tvivo.auth.AccountIdentity
import com.dev.Tvivo.auth.Credentials
import com.dev.Tvivo.data.StreamUrlBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class CatalogType(val apiName: String, val title: String) {
    LIVE("live", "Live TV"), MOVIES("vod", "Movies"), SERIES("series", "Series"),
}

data class DesktopCategory(val id: String, val name: String, val count: Int? = null, val virtual: Boolean = false)
data class DesktopItem(
    val id: String,
    val type: CatalogType,
    val categoryId: String,
    val title: String,
    val artwork: String?,
    val extension: String?,
    val rating: String?,
    val plot: String?,
)
data class DesktopEpisode(val id: String, val season: String, val title: String, val extension: String, val duration: String?, val resumeMs: Long = 0L, val episodeNumber: String? = null)
data class DesktopSeriesResume(val series: DesktopItem, val episode: DesktopEpisode)
data class AccountInfo(val status: String?, val expires: String?, val maxConnections: String?)

/**
 * Some panels template titles as `"{title} ({quality})"` and substitute an empty string
 * when quality metadata is missing, leaving the punctuation behind: `"Movie Name ()"`,
 * `"Movie Name () ()"`, or a title that is *only* `"HD ()"`. Mirrors the same rule in the
 * Android app's `NameNormalizer` (app/src/main/java/com/dev/Tvivo/data/local/
 * NameNormalizer.kt) -- duplicated here rather than shared because `desktop` depends only
 * on `shared-core`, not the `app` module `NameNormalizer` lives in; if a third place ever
 * needs this rule, move it to `shared-core` instead of duplicating a third time.
 */
private val EMPTY_BRACKETS = Regex("[\\(\\[][\\s\\-:|]*[\\)\\]]")
private val WHITESPACE = Regex("\\s+")
private fun cleanTitle(name: String?): String {
    val cleaned = WHITESPACE.replace(EMPTY_BRACKETS.replace(name ?: "", " "), " ").trim()
    return cleaned.ifBlank { "Untitled" }
}

/** Desktop cache. Schema versions are migrated in place; a catalog is always scoped to its account hash. */
internal class DesktopCatalogRepository(private val credentials: Credentials) : AutoCloseable {
    private val accountId = AccountIdentity.of(credentials)
    private val database = Path.of(System.getenv("APPDATA") ?: ".", "Tvivo", "catalog.db")
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    private val refreshMutex = Mutex()

    init {
        Files.createDirectories(database.parent)
        connection().use { db -> migrate(db) }
    }

    fun categories(type: CatalogType): List<DesktopCategory> = connection().use { db ->
        db.prepareStatement("SELECT id,name FROM categories WHERE account_id=? AND type=? ORDER BY position,name").use { statement ->
            statement.setString(1, accountId); statement.setString(2, type.name)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(DesktopCategory(rows.getString(1), rows.getString(2))) } }
        }
    }

    suspend fun ensureLoaded(type: CatalogType) {
        val hasRows = connection().use { db ->
            db.prepareStatement("SELECT 1 FROM items WHERE account_id=? AND type=? LIMIT 1").use { statement ->
                statement.setString(1, accountId); statement.setString(2, type.name)
                statement.executeQuery().use { it.next() }
            }
        }
        if (!hasRows) refresh(type)
    }

    fun items(type: CatalogType, category: String, query: String): List<DesktopItem> = connection().use { db ->
        // D-Desktop-16b: series continuation lives in episode_resume (one row per episode), not on
        // the series row's own resume_ms -- recordResume() never writes items.resume_ms for a
        // series play (see recordResume below). "__continue" for SERIES must join the episode
        // table instead, or it silently returns nothing.
        if (type == CatalogType.SERIES && category == "__continue") {
            // Codex review (2026-09-15): match the resume_ms>0 semantics the MOVIES/LIVE branch
            // below uses -- a zero-position episode_resume row (finished/reset) must not count as
            // "continue watching" just because a row exists.
            db.prepareStatement(
                "SELECT i.id,i.category_id,i.title,i.artwork,i.extension,i.rating,i.plot FROM items i " +
                    "JOIN (SELECT series_id, MAX(updated_at) AS last_watched FROM episode_resume WHERE account_id=? AND position_ms>0 GROUP BY series_id) e " +
                    "ON e.series_id=i.id WHERE i.account_id=? AND i.type=? AND i.title LIKE ? ORDER BY e.last_watched DESC LIMIT 500"
            ).use { statement ->
                statement.setString(1, accountId); statement.setString(2, accountId); statement.setString(3, type.name); statement.setString(4, "%${query.trim()}%")
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(item(rows, type)) } }
            }
        } else {
            val virtual = category.startsWith("__")
            val where = when (category) {
                "__favourites" -> "favourite=1"
                "__continue" -> "resume_ms>0"
                "__recent" -> "1=1"
                "__all" -> "1=1"
                else -> "category_id=?"
            }
            val order = if (category == "__recent") "added_at DESC" else "title COLLATE NOCASE"
            db.prepareStatement("SELECT id,category_id,title,artwork,extension,rating,plot FROM items WHERE account_id=? AND type=? AND $where AND title LIKE ? ORDER BY $order LIMIT 500").use { statement ->
                var i = 1; statement.setString(i++, accountId); statement.setString(i++, type.name)
                if (!virtual) statement.setString(i++, category)
                statement.setString(i, "%${query.trim()}%")
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(item(rows, type)) } }
            }
        }
    }

    suspend fun refresh(type: CatalogType) = refreshMutex.withLock {
        val categories = request("get_${type.apiName}_categories").asJsonArray
        val items = request(if (type == CatalogType.SERIES) "get_series" else "get_${type.apiName}_streams").asJsonArray
        check(items.size() > 0) { "Provider returned an empty catalog; existing offline data was kept." }
        connection().use { db ->
            db.autoCommit = false
            try {
                val savedState = db.prepareStatement("SELECT id,favourite,resume_ms,resume_updated_at FROM items WHERE account_id=? AND type=? AND (favourite=1 OR resume_ms>0)").use { state ->
                    state.setString(1, accountId); state.setString(2, type.name)
                    state.executeQuery().use { rows -> buildList { while (rows.next()) add(listOf(rows.getString(1), rows.getLong(2).toString(), rows.getLong(3).toString(), rows.getLong(4).toString())) } }
                }
                db.prepareStatement("DELETE FROM categories WHERE account_id=? AND type=?").use { it.setString(1, accountId); it.setString(2, type.name); it.executeUpdate() }
                db.prepareStatement("DELETE FROM items WHERE account_id=? AND type=?").use { it.setString(1, accountId); it.setString(2, type.name); it.executeUpdate() }
                db.prepareStatement("INSERT INTO categories(account_id,type,id,name,position) VALUES(?,?,?,?,?)").use { insert ->
                    categories.forEachIndexed { index, raw -> raw.asJsonObject.let { row ->
                        insert.setString(1, accountId); insert.setString(2, type.name); insert.setString(3, row.string("category_id") ?: return@let)
                        insert.setString(4, row.string("category_name") ?: "Unnamed category"); insert.setInt(5, index); insert.addBatch()
                    }}; insert.executeBatch()
                }
                db.prepareStatement("INSERT INTO items(account_id,type,id,category_id,title,artwork,extension,rating,plot,added_at) VALUES(?,?,?,?,?,?,?,?,?,?)").use { insert ->
                    items.forEach { raw -> insertItem(insert, type, raw.asJsonObject) }; insert.executeBatch()
                }
                db.prepareStatement("UPDATE items SET favourite=?,resume_ms=?,resume_updated_at=? WHERE account_id=? AND type=? AND id=?").use { restore ->
                    savedState.forEach { state ->
                        restore.setLong(1, state[1].toLong()); restore.setLong(2, state[2].toLong()); restore.setLong(3, state[3].toLong())
                        restore.setString(4, accountId); restore.setString(5, type.name); restore.setString(6, state[0]); restore.addBatch()
                    }; restore.executeBatch()
                }
                db.commit()
            } catch (failure: Throwable) { db.rollback(); throw failure } finally { db.autoCommit = true }
        }
    }

    fun toggleFavourite(item: DesktopItem) = connection().use { db ->
        db.prepareStatement("UPDATE items SET favourite=CASE favourite WHEN 1 THEN 0 ELSE 1 END WHERE account_id=? AND type=? AND id=?").use {
            it.setString(1, accountId); it.setString(2, item.type.name); it.setString(3, item.id); it.executeUpdate()
        }
    }

    fun recordResume(item: DesktopItem, episode: DesktopEpisode?, positionMs: Long) = connection().use { db ->
        val contentType = if (episode == null) item.type.name else CatalogType.SERIES.name
        val contentId = episode?.id ?: item.id
        db.prepareStatement("INSERT INTO resume_positions(account_id,content_type,content_id,position_ms,updated_at) VALUES(?,?,?,?,?) ON CONFLICT(account_id,content_type,content_id) DO UPDATE SET position_ms=excluded.position_ms,updated_at=excluded.updated_at").use {
            it.setString(1, accountId); it.setString(2, contentType); it.setString(3, contentId); it.setLong(4, positionMs); it.setLong(5, System.currentTimeMillis()); it.executeUpdate()
        }
        if (episode != null) {
            db.prepareStatement("INSERT INTO episode_resume(account_id,episode_id,series_id,season,episode_number,episode_title,extension,duration,position_ms,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(account_id,episode_id) DO UPDATE SET series_id=excluded.series_id,season=excluded.season,episode_number=excluded.episode_number,episode_title=excluded.episode_title,extension=excluded.extension,duration=excluded.duration,position_ms=excluded.position_ms,updated_at=excluded.updated_at").use {
                it.setString(1, accountId); it.setString(2, episode.id); it.setString(3, item.id); it.setString(4, episode.season); it.setString(5, episode.episodeNumber)
                it.setString(6, episode.title); it.setString(7, episode.extension); it.setString(8, episode.duration); it.setLong(9, positionMs); it.setLong(10, System.currentTimeMillis()); it.executeUpdate()
            }
            return@use
        }
        db.prepareStatement("UPDATE items SET resume_ms=?, resume_updated_at=? WHERE account_id=? AND type=? AND id=?").use {
            it.setLong(1, positionMs); it.setLong(2, System.currentTimeMillis()); it.setString(3, accountId); it.setString(4, item.type.name); it.setString(5, item.id); it.executeUpdate()
        }
    }

    fun recentItems(type: CatalogType, limit: Int = 3): List<DesktopItem> = connection().use { db ->
        db.prepareStatement("SELECT id,category_id,title,artwork,extension,rating,plot FROM items WHERE account_id=? AND type=? AND resume_ms>0 ORDER BY resume_updated_at DESC LIMIT ?").use { statement ->
            statement.setString(1, accountId); statement.setString(2, type.name); statement.setInt(3, limit)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(item(rows, type)) } }
        }
    }

    fun recentEpisodes(limit: Int = 3): List<DesktopSeriesResume> = connection().use { db ->
        db.prepareStatement("SELECT i.id,i.category_id,i.title,i.artwork,i.extension,i.rating,i.plot,e.episode_id,e.season,e.episode_title,e.extension,e.duration,e.position_ms,e.episode_number FROM episode_resume e JOIN items i ON i.account_id=e.account_id AND i.type='SERIES' AND i.id=e.series_id WHERE e.account_id=? ORDER BY e.updated_at DESC LIMIT ?").use { statement ->
            statement.setString(1, accountId); statement.setInt(2, limit)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) {
                val series = item(rows, CatalogType.SERIES)
                val episode = DesktopEpisode(rows.getString(8), rows.getString(9), rows.getString(10), rows.getString(11), rows.getString(12), rows.getLong(13), rows.getString(14))
                add(DesktopSeriesResume(series, episode))
            } } }
        }
    }

    fun episodes(series: DesktopItem): List<DesktopEpisode> {
        val root = request("get_series_info", mapOf("series_id" to series.id)).asJsonObject
        val episodes = root.getAsJsonObject("episodes") ?: return emptyList()
        return episodes.entrySet().flatMap { (season, value) -> value.asJsonArray.mapNotNull { raw -> raw.asJsonObject.let { row ->
            val id = row.string("id") ?: row.string("episode_id") ?: return@let null
            DesktopEpisode(id, season, row.string("title") ?: "Episode", row.string("container_extension") ?: "mp4", row.string("duration"), resumePosition(CatalogType.SERIES, id), row.string("episode_num"))
        } } }
    }

    fun resumePosition(type: CatalogType, id: String): Long = connection().use { db ->
        db.prepareStatement("SELECT position_ms FROM resume_positions WHERE account_id=? AND content_type=? AND content_id=?").use {
            it.setString(1, accountId); it.setString(2, type.name); it.setString(3, id)
            it.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0L }
        }
    }

    fun accountInfo(): AccountInfo {
        val root = request().asJsonObject
        val user = root.getAsJsonObject("user_info")
        return AccountInfo(user?.string("status"), user?.string("exp_date"), user?.string("max_connections"))
    }

    fun playbackUrl(item: DesktopItem, episode: DesktopEpisode? = null): String = when {
        episode != null -> StreamUrlBuilder.episode(credentials, episode.id, episode.extension)
        item.type == CatalogType.LIVE -> StreamUrlBuilder.live(credentials, item.id.toInt(), item.extension ?: "ts")
        else -> StreamUrlBuilder.movie(credentials, item.id.toInt(), item.extension ?: "mp4")
    }

    private fun request(action: String? = null, extra: Map<String, String> = emptyMap()): JsonElement {
        val values = buildMap { put("username", credentials.username); put("password", credentials.password); action?.let { put("action", it) }; putAll(extra) }
        val query = values.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8).replace("+", "%20")}" }
        val response = client.send(HttpRequest.newBuilder(URI.create("${credentials.playerApiUrl()}?$query")).timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "Provider request failed (${response.statusCode()})." }
        return JsonParser.parseString(response.body())
    }

    private fun connection() = DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}")
    private fun migrate(db: java.sql.Connection) {
        db.createStatement().use { sql ->
            sql.execute("CREATE TABLE IF NOT EXISTS schema_version(version INTEGER NOT NULL)")
            val version = sql.executeQuery("SELECT version FROM schema_version LIMIT 1").use { if (it.next()) it.getInt(1) else 0 }
            if (version == 0) {
                sql.execute("CREATE TABLE categories(account_id TEXT NOT NULL,type TEXT NOT NULL,id TEXT NOT NULL,name TEXT NOT NULL,position INTEGER NOT NULL,PRIMARY KEY(account_id,type,id))")
                sql.execute("CREATE TABLE items(account_id TEXT NOT NULL,type TEXT NOT NULL,id TEXT NOT NULL,category_id TEXT NOT NULL,title TEXT NOT NULL,artwork TEXT,extension TEXT,rating TEXT,plot TEXT,added_at INTEGER NOT NULL,favourite INTEGER NOT NULL DEFAULT 0,resume_ms INTEGER NOT NULL DEFAULT 0,resume_updated_at INTEGER,PRIMARY KEY(account_id,type,id))")
                sql.execute("CREATE INDEX items_browse ON items(account_id,type,category_id)")
                sql.execute("INSERT INTO schema_version VALUES(1)")
            }
            if (version < 2) {
                sql.execute("CREATE TABLE IF NOT EXISTS resume_positions(account_id TEXT NOT NULL,content_type TEXT NOT NULL,content_id TEXT NOT NULL,position_ms INTEGER NOT NULL,updated_at INTEGER NOT NULL,PRIMARY KEY(account_id,content_type,content_id))")
                sql.execute("INSERT OR IGNORE INTO resume_positions(account_id,content_type,content_id,position_ms,updated_at) SELECT account_id,type,id,resume_ms,COALESCE(resume_updated_at,0) FROM items WHERE resume_ms>0")
                sql.execute("UPDATE schema_version SET version=2")
            }
            if (version < 3) {
                sql.execute("CREATE TABLE IF NOT EXISTS episode_resume(account_id TEXT NOT NULL,episode_id TEXT NOT NULL,series_id TEXT NOT NULL,season TEXT NOT NULL,episode_number TEXT,episode_title TEXT NOT NULL,extension TEXT NOT NULL,duration TEXT,position_ms INTEGER NOT NULL,updated_at INTEGER NOT NULL,PRIMARY KEY(account_id,episode_id))")
                sql.execute("CREATE INDEX IF NOT EXISTS episode_resume_recent ON episode_resume(account_id,updated_at DESC)")
                sql.execute("UPDATE schema_version SET version=3")
            }
        }
    }
    private fun insertItem(statement: java.sql.PreparedStatement, type: CatalogType, row: JsonObject) {
        val id = if (type == CatalogType.SERIES) row.string("series_id") else row.string("stream_id")
        if (id == null) return
        statement.setString(1, accountId); statement.setString(2, type.name); statement.setString(3, id); statement.setString(4, row.string("category_id") ?: "")
        statement.setString(5, cleanTitle(row.string("name"))); statement.setString(6, row.string(if (type == CatalogType.SERIES) "cover" else "stream_icon"))
        statement.setString(7, row.string(if (type == CatalogType.LIVE) "ext" else "container_extension") ?: if (type == CatalogType.LIVE) "ts" else "mp4")
        statement.setString(8, row.string("rating")); statement.setString(9, row.string("plot")); statement.setLong(10, row.string("added")?.toLongOrNull() ?: System.currentTimeMillis()); statement.addBatch()
    }
    private fun item(rows: java.sql.ResultSet, type: CatalogType) = DesktopItem(rows.getString(1), type, rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6), rows.getString(7))
    private fun JsonObject.string(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString
    override fun close() = Unit
}
