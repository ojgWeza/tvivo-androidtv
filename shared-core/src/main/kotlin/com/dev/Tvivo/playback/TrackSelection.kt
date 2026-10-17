package com.dev.Tvivo.playback

import java.util.Locale
import java.util.MissingResourceException

enum class TrackKind { Audio, Subtitle }

data class PlaybackTrack(
    val kind: TrackKind,
    val key: String,
    val displayName: String,
    val languageCode: String?,
    val isSelected: Boolean,
)

data class PlaybackTrackSnapshot(
    val audio: List<PlaybackTrack>,
    val subtitles: List<PlaybackTrack>,
    val isResolved: Boolean,
) {
    companion object { val Unresolved = PlaybackTrackSnapshot(emptyList(), emptyList(), false) }
}

enum class PreferredAudio(val languageCode: String?) { Auto(null), Arabic("ar"), English("en") }
enum class PreferredSubtitles(val languageCode: String?) { Off(null), Arabic("ar"), English("en") }

data class TrackPreferences(
    val audio: PreferredAudio = PreferredAudio.Auto,
    val subtitles: PreferredSubtitles = PreferredSubtitles.Off,
) {
    companion object { val AndroidDefaults = TrackPreferences() }
}

object TrackSelectionPolicy {
    private val separators = charArrayOf(' ', '-', '_', '(', ')', '[', ']', '/', '\\', '.', ':')
    private val languageAliases: Map<String, String> = buildMap {
        for (code in Locale.getISOLanguages()) {
            if (code != "iv") {
                putIfAbsent(code.lowercase(Locale.ROOT), code.lowercase(Locale.ROOT))
                try {
                    putIfAbsent(Locale(code).isO3Language.lowercase(Locale.ROOT), code.lowercase(Locale.ROOT))
                } catch (_: MissingResourceException) { /* .NET also ignores unavailable aliases. */ }
            }
        }
    }

    fun pickSubtitle(
        tracks: List<PlaybackTrack>,
        preferredLanguage: String?,
        explicitOff: Boolean,
        uiCultureTwoLetterCode: String?,
    ): String? {
        if (explicitOff || tracks.isEmpty()) return null
        return findLanguageMatch(tracks, preferredLanguage)?.key
            ?: findLanguageMatch(tracks, uiCultureTwoLetterCode)?.key
            ?: tracks.first().key
    }

    fun pickAudio(
        tracks: List<PlaybackTrack>,
        preferredLanguage: String?,
        uiCultureTwoLetterCode: String?,
    ): String? {
        if (tracks.isEmpty()) return null
        return findLanguageMatch(tracks, preferredLanguage)?.key
            ?: findLanguageMatch(tracks, uiCultureTwoLetterCode)?.key
    }

    fun getDisplayName(kind: TrackKind, sourceLabel: String?, languageCode: String?, ordinal: Int): String {
        val label = sourceLabel?.trim()
        if (!label.isNullOrEmpty() && !looksLikeOpaqueLanguageOrOrdinal(label, languageCode, kind, ordinal)) return label
        val languageName = languageName(languageCode)
        if (languageName != null) return languageName
        return "${if (kind == TrackKind.Audio) "Audio" else "Subtitle"} track $ordinal"
    }

    fun normalizeLanguageCode(languageCode: String?): String? {
        if (languageCode.isNullOrBlank()) return null
        val primary = languageCode.trim().split('-', '_').first()
        if (primary.isEmpty()) return null
        return languageAliases[primary.lowercase(Locale.ROOT)] ?: primary.lowercase(Locale.ROOT)
    }

    private fun findLanguageMatch(tracks: List<PlaybackTrack>, requestedLanguage: String?): PlaybackTrack? {
        val requestedCode = normalizeLanguageCode(requestedLanguage) ?: return null
        val matches = tracks.filter { normalizeLanguageCode(it.languageCode) == requestedCode }
        return matches.firstOrNull { !hasClearlySecondaryLabel(it.displayName) } ?: matches.firstOrNull()
    }

    private fun languageName(languageCode: String?): String? {
        val normalized = normalizeLanguageCode(languageCode) ?: return null
        if (normalized !in Locale.getISOLanguages()) return null
        return Locale(normalized).getDisplayLanguage(Locale.ENGLISH).takeIf { it.isNotBlank() }
    }

    private fun looksLikeOpaqueLanguageOrOrdinal(
        label: String, languageCode: String?, kind: TrackKind, ordinal: Int,
    ): Boolean {
        val normalized = normalizeLanguageCode(label)
        if (normalized != null && normalized == normalizeLanguageCode(languageCode)) return true
        return label == ordinal.toString() || label.equals("Track $ordinal", ignoreCase = true) ||
            label.equals(if (kind == TrackKind.Audio) "Audio" else "Subtitle", ignoreCase = true)
    }

    private fun hasClearlySecondaryLabel(displayName: String): Boolean =
        displayName.split(*separators).any {
            it.equals("forced", ignoreCase = true) || it.equals("commentary", ignoreCase = true) ||
                it.equals("sdh", ignoreCase = true)
        }
}
