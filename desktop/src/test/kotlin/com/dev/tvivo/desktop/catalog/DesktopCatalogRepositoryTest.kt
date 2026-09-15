package com.dev.tvivo.desktop.catalog

import com.dev.Tvivo.auth.AccountIdentity
import com.dev.Tvivo.auth.Credentials
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopCatalogRepositoryTest {
    @Test fun `refresh normalizes provider added seconds to milliseconds`() = withRepository { repository, database, credentials, server ->
        server.movies = """[{"stream_id":"movie-1","category_id":"movies","name":"Movie","container_extension":"mp4","added":"1700000000"}]"""

        runBlocking { repository.refresh(CatalogType.MOVIES) }

        assertEquals(1_700_000_000_000L, itemLong(database, credentials, CatalogType.MOVIES, "movie-1", "added_at"))
    }

    @Test fun `refresh retains first indexed time while provider added time changes`() = withRepository { repository, database, credentials, server ->
        server.movies = """[{"stream_id":"movie-1","category_id":"movies","name":"Movie","container_extension":"mp4","added":"1700000000"}]"""
        runBlocking { repository.refresh(CatalogType.MOVIES) }
        val firstIndexedAt = itemLong(database, credentials, CatalogType.MOVIES, "movie-1", "first_indexed_at")

        server.movies = """[{"stream_id":"movie-1","category_id":"movies","name":"Movie","container_extension":"mp4","added":"1700000010"}]"""
        runBlocking { repository.refresh(CatalogType.MOVIES) }

        assertEquals(firstIndexedAt, itemLong(database, credentials, CatalogType.MOVIES, "movie-1", "first_indexed_at"))
        assertEquals(1_700_000_010_000L, itemLong(database, credentials, CatalogType.MOVIES, "movie-1", "added_at"))
    }

    @Test fun `record tuned does not create a live resume`() = withRepository { repository, database, credentials, server ->
        server.live = """[{"stream_id":"live-1","category_id":"live","name":"Live","ext":"ts","added":"1700000000"}]"""
        runBlocking { repository.refresh(CatalogType.LIVE) }
        val live = repository.items(CatalogType.LIVE, "__all", "").single()

        repository.recordTuned(live)

        assertEquals(0L, itemLong(database, credentials, CatalogType.LIVE, "live-1", "resume_ms"))
        assertNull(itemNullableLong(database, credentials, CatalogType.LIVE, "live-1", "resume_updated_at"))
        // DesktopShell guards recordResume() for LIVE; the repository deliberately keeps its
        // generic resume method usable for non-player callers, so that UI guard is not duplicated here.
        assertEquals(listOf("live-1"), repository.recentItems(CatalogType.LIVE).map { it.id })
    }

    @Test fun `live recent items order by tune history rather than resume history`() = withRepository { repository, database, credentials, server ->
        server.live = """[
            {"stream_id":"live-1","category_id":"live","name":"First","ext":"ts","added":"1700000000"},
            {"stream_id":"live-2","category_id":"live","name":"Second","ext":"ts","added":"1700000001"}
        ]"""
        runBlocking { repository.refresh(CatalogType.LIVE) }
        setItemTimes(database, credentials, "live-1", resumeUpdatedAt = 900L, lastTunedAt = 100L)
        setItemTimes(database, credentials, "live-2", resumeUpdatedAt = 100L, lastTunedAt = 900L)

        assertEquals(listOf("live-2", "live-1"), repository.recentItems(CatalogType.LIVE, 2).map { it.id })
    }

    @Test fun `recent episodes keeps only each series most recently played resumable episode`() = withRepository { repository, database, credentials, server ->
        server.series = """[
            {"series_id":"series-a","category_id":"series","name":"Series A","cover":"https://example.com/a.jpg"},
            {"series_id":"series-b","category_id":"series","name":"Series B","cover":"https://example.com/b.jpg"}
        ]"""
        runBlocking { repository.refresh(CatalogType.SERIES) }
        insertEpisodeResume(database, credentials, "episode-5", "series-a", positionMs = 100L, updatedAt = 100L)
        insertEpisodeResume(database, credentials, "episode-2", "series-a", positionMs = 200L, updatedAt = 300L)
        insertEpisodeResume(database, credentials, "episode-1", "series-b", positionMs = 100L, updatedAt = 200L)
        insertEpisodeResume(database, credentials, "finished", "series-a", positionMs = 0L, updatedAt = 400L)

        assertEquals(listOf("episode-2", "episode-1"), repository.recentEpisodes().map { it.episode.id })
    }

    @Test fun `suggestions stay stable across repeated home and browse access`() = withRepository { repository, _, _, server ->
        server.movies = movieFixtures(20)
        runBlocking { repository.refresh(CatalogType.MOVIES) }

        val homeSnapshot = repository.ensureSuggestions(CatalogType.MOVIES, emptySet())
        val browseSnapshot = repository.items(CatalogType.MOVIES, "__suggestions", "")
        val returnedHomeSnapshot = repository.ensureSuggestions(CatalogType.MOVIES, emptySet())

        assertEquals(homeSnapshot.map { it.id }.toSet(), browseSnapshot.map { it.id }.toSet())
        assertEquals(homeSnapshot.map { it.id }.toSet(), returnedHomeSnapshot.map { it.id }.toSet())
    }

    @Test fun `regenerated suggestions update the home snapshot used by browse`() = withRepository { repository, _, _, server ->
        server.movies = movieFixtures(20)
        runBlocking { repository.refresh(CatalogType.MOVIES) }

        val regenerated = repository.regenerateSuggestions(CatalogType.MOVIES, emptySet())

        assertEquals(regenerated.map { it.id }.toSet(), repository.items(CatalogType.MOVIES, "__suggestions", "").map { it.id }.toSet())
    }

    @Test fun `suggestions exclude ids already visible in continuation shelves`() = withRepository { repository, _, _, server ->
        server.movies = movieFixtures(6)
        runBlocking { repository.refresh(CatalogType.MOVIES) }

        val suggestions = repository.ensureSuggestions(CatalogType.MOVIES, setOf("movie-1", "movie-2"), limit = 6)

        assertTrue(suggestions.none { it.id in setOf("movie-1", "movie-2") })
    }

    @Test fun `suggestion browse is empty before a session snapshot exists`() = withRepository { repository, _, _, server ->
        server.movies = movieFixtures(2)
        runBlocking { repository.refresh(CatalogType.MOVIES) }

        assertEquals(emptyList<DesktopItem>(), repository.items(CatalogType.MOVIES, "__suggestions", ""))
    }

    @Test fun `failed refresh leaves the existing suggestion snapshot stable`() = withRepository { repository, _, _, server ->
        server.movies = movieFixtures(20)
        runBlocking { repository.refresh(CatalogType.MOVIES) }
        val beforeFailure = repository.ensureSuggestions(CatalogType.MOVIES, emptySet()).map { it.id }.toSet()
        server.movies = "[]"

        assertTrue(runCatching { runBlocking { repository.refresh(CatalogType.MOVIES) } }.isFailure)

        assertEquals(beforeFailure, repository.ensureSuggestions(CatalogType.MOVIES, emptySet()).map { it.id }.toSet())
    }

    private fun withRepository(block: (DesktopCatalogRepository, Path, Credentials, FixtureServer) -> Unit) {
        val database = Files.createTempFile("tvivo-catalog-test", ".db")
        FixtureServer().use { server ->
            val credentials = Credentials("127.0.0.1", server.port, "test-user", "test-password")
            try {
                DesktopCatalogRepository(credentials, database).use { repository -> block(repository, database, credentials, server) }
            } finally {
                Files.deleteIfExists(database)
            }
        }
    }

    private fun itemLong(database: Path, credentials: Credentials, type: CatalogType, id: String, column: String): Long =
        itemNullableLong(database, credentials, type, id, column) ?: error("Expected $column for $id")

    private fun itemNullableLong(database: Path, credentials: Credentials, type: CatalogType, id: String, column: String): Long? =
        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { db ->
            db.prepareStatement("SELECT $column FROM items WHERE account_id=? AND type=? AND id=?").use { statement ->
                statement.setString(1, AccountIdentity.of(credentials)); statement.setString(2, type.name); statement.setString(3, id)
                statement.executeQuery().use { rows -> check(rows.next()); rows.getLong(1).takeUnless { rows.wasNull() } }
            }
        }

    private fun setItemTimes(database: Path, credentials: Credentials, id: String, resumeUpdatedAt: Long, lastTunedAt: Long) {
        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { db ->
            db.prepareStatement("UPDATE items SET resume_ms=1,resume_updated_at=?,last_tuned_at=? WHERE account_id=? AND type='LIVE' AND id=?").use { statement ->
                statement.setLong(1, resumeUpdatedAt); statement.setLong(2, lastTunedAt); statement.setString(3, AccountIdentity.of(credentials)); statement.setString(4, id); statement.executeUpdate()
            }
        }
    }

    private fun insertEpisodeResume(database: Path, credentials: Credentials, episodeId: String, seriesId: String, positionMs: Long, updatedAt: Long) {
        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { db ->
            db.prepareStatement("INSERT INTO episode_resume(account_id,episode_id,series_id,season,episode_number,episode_title,extension,duration,position_ms,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)").use { statement ->
                statement.setString(1, AccountIdentity.of(credentials)); statement.setString(2, episodeId); statement.setString(3, seriesId); statement.setString(4, "1"); statement.setString(5, "1")
                statement.setString(6, episodeId); statement.setString(7, "mp4"); statement.setString(8, null); statement.setLong(9, positionMs); statement.setLong(10, updatedAt); statement.executeUpdate()
            }
        }
    }

    private fun movieFixtures(count: Int) = (1..count).joinToString(prefix = "[", postfix = "]") { number ->
        """{"stream_id":"movie-$number","category_id":"movies","name":"Movie $number","container_extension":"mp4","added":"1700000000"}"""
    }

    private class FixtureServer : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var movies = "[]"
        var live = "[]"
        var series = "[]"
        val port get() = server.address.port

        init {
            server.createContext("/player_api.php") { exchange ->
                val action = exchange.requestURI.query.substringAfter("action=", "").substringBefore('&')
                val body = when (action) {
                    "get_vod_categories" -> """[{"category_id":"movies","category_name":"Movies"}]"""
                    "get_vod_streams" -> movies
                    "get_live_categories" -> """[{"category_id":"live","category_name":"Live"}]"""
                    "get_live_streams" -> live
                    "get_series_categories" -> """[{"category_id":"series","category_name":"Series"}]"""
                    "get_series" -> series
                    else -> error("Unexpected action: $action")
                }
                exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }
}
