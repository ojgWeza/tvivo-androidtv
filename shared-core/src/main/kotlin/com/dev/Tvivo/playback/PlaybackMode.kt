package com.dev.Tvivo.playback

enum class PlaybackMode { Off, Next, Shuffle }
enum class MinimizeToMiniMode { MiniPlayerWhilePlaying, MiniPlayerWhilePlayingOrPaused, AlwaysMinimizeNormally }

object PlaybackModeLogic {
    fun shouldAutoAdvance(mode: PlaybackMode): Boolean = mode == PlaybackMode.Next || mode == PlaybackMode.Shuffle
    fun shouldShowNextPrompt(mode: PlaybackMode): Boolean = shouldAutoAdvance(mode)
    fun shouldShuffle(mode: PlaybackMode): Boolean = mode == PlaybackMode.Shuffle

    fun fromLegacyAutoplay(autoplayNext: Boolean?, isMovie: Boolean): PlaybackMode = when (autoplayNext) {
        true -> PlaybackMode.Next
        false -> if (isMovie) PlaybackMode.Shuffle else PlaybackMode.Off
        null -> if (isMovie) PlaybackMode.Shuffle else PlaybackMode.Next
    }
}

data class PlaybackModePreferences(
    val episodeMode: PlaybackMode,
    val movieMode: PlaybackMode,
    val minimizeToMiniMode: MinimizeToMiniMode = MinimizeToMiniMode.MiniPlayerWhilePlaying,
) {
    fun toJson(): String =
        "{\"EpisodeMode\":\"${episodeMode.name}\",\"MovieMode\":\"${movieMode.name}\",\"MinimizeToMiniMode\":\"${minimizeToMiniMode.name}\"}"

    companion object {
        val WinUiDefaults = PlaybackModePreferences(PlaybackMode.Next, PlaybackMode.Shuffle)
        val AndroidDefaults = PlaybackModePreferences(PlaybackMode.Next, PlaybackMode.Off)

        /** [defaults] is explicit because fresh Android and WinUI settings have different movie modes. */
        fun fromJson(json: String, defaults: PlaybackModePreferences = WinUiDefaults): PlaybackModePreferences {
            val properties = FlatJsonObject.read(json) ?: return defaults
            val episode = readEnum<PlaybackMode>(properties["episodemode"])
                ?: readLegacy(properties["episodeautoplaynext"], false, defaults.episodeMode)
            val movie = readEnum<PlaybackMode>(properties["moviemode"])
                ?: readLegacy(properties["movieautoplaynext"], true, defaults.movieMode)
            val mini = readEnum<MinimizeToMiniMode>(properties["minimizetominimode"])
                ?: if (properties["minimizetominiplayerwhileplaying"] == FlatValue("false", false))
                    MinimizeToMiniMode.AlwaysMinimizeNormally
                else MinimizeToMiniMode.MiniPlayerWhilePlaying
            return PlaybackModePreferences(episode, movie, mini)
        }

        private fun readLegacy(value: FlatValue?, isMovie: Boolean, default: PlaybackMode): PlaybackMode = when (value) {
            FlatValue("true", false) -> PlaybackModeLogic.fromLegacyAutoplay(true, isMovie)
            FlatValue("false", false) -> PlaybackModeLogic.fromLegacyAutoplay(false, isMovie)
            else -> default
        }

        private inline fun <reified T : Enum<T>> readEnum(value: FlatValue?): T? {
            if (value?.quoted != true) return null
            return enumValues<T>().firstOrNull { it.name.equals(value.text, ignoreCase = true) }
                ?: value.text.toIntOrNull()?.let { enumValues<T>().getOrNull(it) }
        }
    }
}

/** Minimal reader for the flat, string/bool settings emitted by both clients; no runtime JSON dependency. */
private data class FlatValue(val text: String, val quoted: Boolean)

private object FlatJsonObject {
    fun read(json: String): Map<String, FlatValue>? {
        var index = 0
        fun whitespace() { while (index < json.length && json[index].isWhitespace()) index++ }
        fun quoted(): String? {
            if (index >= json.length || json[index++] != '"') return null
            val value = StringBuilder()
            while (index < json.length) {
                val char = json[index++]
                if (char == '"') return value.toString()
                if (char != '\\') { value.append(char); continue }
                if (index >= json.length) return null
                when (val escaped = json[index++]) {
                    '"', '\\', '/' -> value.append(escaped)
                    'b' -> value.append('\b')
                    'f' -> value.append('\u000c')
                    'n' -> value.append('\n')
                    'r' -> value.append('\r')
                    't' -> value.append('\t')
                    'u' -> {
                        if (index + 4 > json.length) return null
                        val code = json.substring(index, index + 4).toIntOrNull(16) ?: return null
                        value.append(code.toChar())
                        index += 4
                    }
                    else -> return null
                }
            }
            return null
        }
        whitespace()
        if (index >= json.length || json[index++] != '{') return null
        whitespace()
        val result = linkedMapOf<String, FlatValue>()
        if (index < json.length && json[index] == '}') {
            index++
            whitespace()
            return if (index == json.length) result else null
        }
        while (index < json.length) {
            val name = quoted()?.lowercase(java.util.Locale.ROOT) ?: return null
            whitespace()
            if (index >= json.length || json[index++] != ':') return null
            whitespace()
            val quotedValue = index < json.length && json[index] == '"'
            val value = if (quotedValue) quoted() else {
                val start = index
                while (index < json.length && json[index] != ',' && json[index] != '}') index++
                json.substring(start, index).trim().takeIf { it == "true" || it == "false" || it == "null" || it.toDoubleOrNull() != null }
            } ?: return null
            result.putIfAbsent(name, FlatValue(value, quotedValue))
            whitespace()
            if (index < json.length && json[index] == '}') {
                index++
                whitespace()
                return if (index == json.length) result else null
            }
            if (index >= json.length || json[index++] != ',') return null
            whitespace()
        }
        return null
    }
}
