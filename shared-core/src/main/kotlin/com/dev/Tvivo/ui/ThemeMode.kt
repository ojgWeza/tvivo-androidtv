package com.dev.Tvivo.ui

/** The two persisted appearance choices. System is intentionally not a stored mode yet. */
enum class ThemeMode {
    Dark, Light;

    fun toPreference(): String = when (this) {
        Dark -> "Dark"
        Light -> "Light"
    }

    companion object {
        /** Match WinUI's exact values; missing, corrupt, or future values keep Dark. */
        fun fromPreference(value: String?): ThemeMode = when (value) {
            "Light" -> Light
            else -> Dark
        }
    }
}
