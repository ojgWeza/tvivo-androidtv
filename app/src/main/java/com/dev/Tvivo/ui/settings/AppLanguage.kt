package com.dev.Tvivo.ui.settings

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

internal enum class AppLanguage(val tag: String) {
    System(""), English("en"), Arabic("ar");

    fun next(): AppLanguage = entries[(ordinal + 1) % entries.size]

    companion object {
        private const val PREFS = "app_language"
        private const val KEY = "language_tag"

        fun current(context: Context): AppLanguage {
            if (Build.VERSION.SDK_INT >= 33) {
                val tags = context.getSystemService(LocaleManager::class.java)
                    .applicationLocales.toLanguageTags()
                if (tags.isNotEmpty()) return fromTag(tags.substringBefore(','))
                return System
            }
            return fromTag(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "").orEmpty())
        }

        fun set(context: Context, language: AppLanguage) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, language.tag).commit()
            if (Build.VERSION.SDK_INT >= 33) {
                context.getSystemService(LocaleManager::class.java).applicationLocales =
                    LocaleList.forLanguageTags(language.tag)
            }
        }

        /** Android 12 and earlier do not provide per-app locales, so wrap each Activity. */
        fun wrap(base: Context): Context {
            if (Build.VERSION.SDK_INT >= 33) return base
            val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "").orEmpty()
            if (tag.isBlank()) return base
            val configuration = Configuration(base.resources.configuration)
            configuration.setLocale(Locale.forLanguageTag(tag))
            return base.createConfigurationContext(configuration)
        }

        private fun fromTag(tag: String): AppLanguage = when (tag.substringBefore('-').lowercase()) {
            "en" -> English
            "ar" -> Arabic
            else -> System
        }
    }
}
