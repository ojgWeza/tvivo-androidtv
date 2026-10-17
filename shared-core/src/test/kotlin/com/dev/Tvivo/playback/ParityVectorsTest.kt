package com.dev.Tvivo.playback

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ParityVectorsTest {
    private fun vectors(file: String): JsonArray {
        val resource = javaClass.getResourceAsStream("/parity/$file.json")
            ?: error("Missing parity fixture: $file")
        return resource.bufferedReader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonArray }
    }

    private fun JsonObject.string(name: String): String = get(name).asString
    private fun JsonObject.stringOrNull(name: String): String? =
        get(name)?.takeUnless { it.isJsonNull }?.asString
    private fun JsonObject.longOrNull(name: String): Long? =
        get(name)?.takeUnless { it.isJsonNull }?.asLong
    private fun JsonObject.bool(name: String, default: Boolean = false): Boolean =
        get(name)?.takeUnless { it.isJsonNull }?.asBoolean ?: default
    private fun JsonObject.objects(name: String): List<JsonObject> =
        getAsJsonArray(name).map { it.asJsonObject }
    private fun JsonObject.strings(name: String): List<String> =
        getAsJsonArray(name).map { it.asString }

    @Test fun episodeCompletion() {
        for (element in vectors("episode-completion")) {
            val vector = element.asJsonObject
            val actual = when (vector.string("op")) {
                "episode" -> EpisodeCompletion.isFinished(vector.get("position").asLong, vector.longOrNull("duration"), vector.bool("ended"))
                "movie" -> MovieCompletion.isFinished(vector.get("position").asLong, vector.longOrNull("duration"), vector.bool("ended"))
                "end" -> PlaybackEndPolicy.isGenuineEnd(vector.get("position").asLong, vector.longOrNull("duration"), vector.bool("ended"))
                else -> error(vector.string("name"))
            }
            assertEquals(vector.string("name"), vector.bool("expected"), actual)
        }
    }

    @Test fun playbackMode() {
        for (element in vectors("playback-mode")) {
            val vector = element.asJsonObject
            val name = vector.string("name")
            when (vector.string("op")) {
                "logic" -> {
                    val mode = PlaybackMode.valueOf(vector.string("mode"))
                    val expected = vector.getAsJsonObject("expected")
                    assertEquals(name, expected.bool("advance"), PlaybackModeLogic.shouldAutoAdvance(mode))
                    assertEquals(name, expected.bool("prompt"), PlaybackModeLogic.shouldShowNextPrompt(mode))
                    assertEquals(name, expected.bool("shuffle"), PlaybackModeLogic.shouldShuffle(mode))
                }
                "legacy" -> assertEquals(name, vector.string("expected"),
                    PlaybackModeLogic.fromLegacyAutoplay(
                        vector.get("autoplay")?.takeUnless { it.isJsonNull }?.asBoolean,
                        vector.bool("isMovie"),
                    ).name)
                "fromJson", "roundTrip", "defaults" -> {
                    val preferences = when (vector.string("op")) {
                        "fromJson" -> PlaybackModePreferences.fromJson(vector.string("json"),
                            if (vector.stringOrNull("platform") == "Android") PlaybackModePreferences.AndroidDefaults
                            else PlaybackModePreferences.WinUiDefaults)
                        "roundTrip" -> {
                            val source = PlaybackModePreferences(
                                PlaybackMode.valueOf(vector.string("episode")),
                                PlaybackMode.valueOf(vector.string("movie")),
                                MinimizeToMiniMode.valueOf(vector.string("mini")),
                            )
                            val json = source.toJson()
                            assertTrue(name, json.contains("\"EpisodeMode\":\"${source.episodeMode.name}\""))
                            assertFalse(name, json.contains("MinimizeToMiniPlayerWhilePlaying"))
                            PlaybackModePreferences.fromJson(json).also { assertEquals(name, source, it) }
                        }
                        else -> if (vector.string("platform") == "Android")
                            PlaybackModePreferences.AndroidDefaults else PlaybackModePreferences.WinUiDefaults
                    }
                    val expected = vector.getAsJsonObject("expected")
                    assertEquals(name, expected.string("episode"), preferences.episodeMode.name)
                    assertEquals(name, expected.string("movie"), preferences.movieMode.name)
                    assertEquals(name, expected.string("mini"), preferences.minimizeToMiniMode.name)
                    if (name.startsWith("Legacy_minimize"))
                        assertFalse(name, preferences.toJson().contains("MinimizeToMiniPlayerWhilePlaying"))
                }
                else -> error(name)
            }
        }
    }

    @Test fun nextPlaybackSelector() {
        for (element in vectors("next-playback-selector")) {
            val vector = element.asJsonObject
            val name = vector.string("name")
            val playlist = vector.strings("playlist")
            val finished = vector.getAsJsonArray("finished")?.map { it.asString }?.toSet()
            val steps = vector.objects("steps")
            if (vector.bool("repeatFromSeed")) {
                fun run(): List<String?> {
                    val selector = NextPlaybackSelector(Random(vector.get("seed").asInt))
                    var current = steps.first().string("current")
                    return steps.map {
                        selector.selectNext(playlist, current, finished, vector.bool("shuffle")).also { next ->
                            current = next ?: error("$name returned null")
                        }
                    }
                }
                val first = run()
                assertEquals(name, first, run())
                assertEquals(name, vector.getAsJsonObject("expected").get("uniqueCount").asInt, first.toSet().size)
            } else {
                val selector = NextPlaybackSelector { vector.get("randomIndex")?.asInt ?: 0 }
                for (step in steps) {
                    if (step.bool("reset")) selector.reset()
                    val actual = selector.selectNext(playlist, step.string("current"), finished, vector.bool("shuffle"))
                    assertEquals(name, step.stringOrNull("expected"), actual)
                    if (vector.bool("shuffle") && actual != null) assertTrue(name, actual != step.string("current"))
                }
            }
        }
    }

    @Test fun nextPrompt() {
        for (element in vectors("next-prompt")) {
            val vector = element.asJsonObject
            assertEquals(vector.string("name"), vector.get("expected").asLong,
                NextPrompt.leadMilliseconds(StreamKind.valueOf(vector.string("kind")), vector.get("duration").asLong))
        }
    }

    @Test fun heldSeekAccelerator() {
        for (element in vectors("held-seek-accelerator")) {
            val vector = element.asJsonObject
            assertEquals(vector.string("name"), vector.get("expected").asLong,
                HeldSeekAccelerator.stepMilliseconds(vector.get("heldMilliseconds").asLong))
        }
    }

    @Test fun trackSelection() {
        for (element in vectors("track-selection")) {
            val vector = element.asJsonObject
            val name = vector.string("name")
            when (vector.string("op")) {
                "audio", "subtitle" -> {
                    val kind = if (vector.string("op") == "audio") TrackKind.Audio else TrackKind.Subtitle
                    val tracks = vector.objects("tracks").map {
                        PlaybackTrack(kind, it.string("key"), it.stringOrNull("label") ?: it.string("key"),
                            it.stringOrNull("language"), false)
                    }
                    val actual = if (kind == TrackKind.Audio)
                        TrackSelectionPolicy.pickAudio(tracks, vector.stringOrNull("preferred"), vector.stringOrNull("ui"))
                    else TrackSelectionPolicy.pickSubtitle(tracks, vector.stringOrNull("preferred"),
                        vector.bool("off"), vector.stringOrNull("ui"))
                    assertEquals(name, vector.stringOrNull("expected"), actual)
                }
                "normalize" -> assertEquals(name, vector.string("expected"),
                    TrackSelectionPolicy.normalizeLanguageCode(vector.stringOrNull("language")))
                "display" -> assertEquals(name, vector.string("expected"),
                    TrackSelectionPolicy.getDisplayName(TrackKind.valueOf(vector.string("kind")),
                        vector.stringOrNull("label"), vector.stringOrNull("language"), vector.get("ordinal").asInt))
                "defaults" -> {
                    val expected = vector.getAsJsonObject("expected")
                    assertEquals(name, expected.string("audio"), TrackPreferences.AndroidDefaults.audio.name)
                    assertEquals(name, expected.string("subtitles"), TrackPreferences.AndroidDefaults.subtitles.name)
                }
                "preference" -> {
                    val actual = if (vector.string("kind") == "Audio")
                        PreferredAudio.valueOf(vector.string("choice")).languageCode
                    else PreferredSubtitles.valueOf(vector.string("choice")).languageCode
                    assertEquals(name, vector.stringOrNull("expected"), actual)
                }
                else -> error(name)
            }
        }
    }

    @Test fun stallRecoveryPolicy() {
        for (element in vectors("stall-recovery-policy")) {
            val vector = element.asJsonObject
            val name = vector.string("name")
            val policy = StallRecoveryPolicy(vector.get("timeout").asLong)
            for (step in vector.objects("steps")) {
                if (step.has("begin")) {
                    policy.beginSession(step.get("begin").asLong, step.bool("recovery"))
                } else if (step.has("advanceSeconds")) {
                    for (second in step.get("advanceSeconds").asInt..step.get("throughSeconds").asInt)
                        assertEquals(name, StallDecision.valueOf(step.string("expected")),
                            policy.observe(step.get("generation").asLong, true, true,
                                second * 1_000L, second * 1_000L))
                } else {
                    assertEquals(name, StallDecision.valueOf(step.string("expected")),
                        policy.observe(step.get("generation").asLong, step.bool("ready", true),
                            step.bool("eligible", true), step.get("position").asLong,
                            step.get("second").asLong * 1_000L))
                }
                if (step.has("attempted")) assertEquals(name, step.bool("attempted"), policy.recoveryAttempted)
            }
        }
    }
}
