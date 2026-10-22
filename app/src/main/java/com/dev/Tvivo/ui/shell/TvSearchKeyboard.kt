package com.dev.Tvivo.ui.shell

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.dev.Tvivo.ui.common.tvClickable
import com.dev.Tvivo.ui.common.tvFocusFrame
import com.dev.Tvivo.ui.theme.AppColors
import com.dev.Tvivo.ui.theme.TvType
import kotlinx.coroutines.delay

internal enum class TvSearchLanguage { ENGLISH, ARABIC }
internal data class TvSearchKey(val label: String, val action: TvSearchAction)
internal enum class TvSearchAction { Character, Space, Backspace, Clear, SwitchLanguage, Done }

internal fun tvSearchKeys(language: TvSearchLanguage): List<TvSearchKey> {
    val letters = if (language == TvSearchLanguage.ENGLISH) "abcdefghijklmnopqrstuvwxyz" else
        "ابتثجحخدذرزسشصضطظعغفقكلمنهوي"
    return buildList {
        letters.forEach { add(TvSearchKey(it.toString(), TvSearchAction.Character)) }
        "0123456789".forEach { add(TvSearchKey(it.toString(), TvSearchAction.Character)) }
        add(TvSearchKey("Space", TvSearchAction.Space))
        add(TvSearchKey("⌫", TvSearchAction.Backspace))
        add(TvSearchKey("Clear", TvSearchAction.Clear))
        add(TvSearchKey(if (language == TvSearchLanguage.ENGLISH) "عربي" else "English", TvSearchAction.SwitchLanguage))
        add(TvSearchKey("Done / Results", TvSearchAction.Done))
    }
}

internal fun tvSearchTarget(index: Int, keyCount: Int, columns: Int, dx: Int, dy: Int): Int {
    val row = index / columns
    val column = index % columns
    val rows = (keyCount + columns - 1) / columns
    return when {
        dx != 0 -> {
            val rowStart = row * columns
            val rowSize = (keyCount - rowStart).coerceAtMost(columns)
            rowStart + Math.floorMod(column + dx, rowSize)
        }
        dy < 0 && row == 0 -> index
        dy > 0 && row == rows - 1 -> index
        else -> (index + dy * columns).coerceIn(0, keyCount - 1)
    }
}

internal fun editTvSearchQuery(query: String, key: TvSearchKey): String = when (key.action) {
    TvSearchAction.Character -> query + key.label
    TvSearchAction.Space -> if (query.isEmpty() || query.last() == ' ') query else "$query "
    TvSearchAction.Backspace -> query.dropLast(1)
    TvSearchAction.Clear -> ""
    TvSearchAction.Done, TvSearchAction.SwitchLanguage -> query
}

internal class RecentSearchStore(context: Context, accountId: String) {
    private val prefs = context.getSharedPreferences("recent_searches", Context.MODE_PRIVATE)
    private val key = "account:${accountId.hashCode().toUInt().toString(16)}"

    fun read(): List<String> = prefs.getString(key, "").orEmpty().split('\n')
        .filter(String::isNotBlank).take(8)

    fun add(query: String): List<String> {
        val cleaned = query.trim()
        if (cleaned.isEmpty()) return read()
        val next = (listOf(cleaned) + read().filterNot { it.equals(cleaned, ignoreCase = true) }).take(8)
        prefs.edit().putString(key, next.joinToString("\n")).apply()
        return next
    }

    fun clear(): List<String> {
        prefs.edit().remove(key).apply()
        return emptyList()
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TvSearchKeyboard(
    query: String,
    onQueryChanged: (String) -> Unit,
    recentSearches: List<String>,
    onRecentSelected: (String) -> Unit,
    onClearRecents: () -> Unit,
    onDone: () -> Unit,
    firstKeyFocus: FocusRequester,
    resultsFocus: FocusRequester,
    onKeyboardFocused: () -> Unit
) {
    var language by remember { mutableStateOf(TvSearchLanguage.ENGLISH) }
    var focusedIndex by remember { mutableIntStateOf(0) }
    val keys = tvSearchKeys(language)
    val requesters = remember(language) { List(keys.size) { FocusRequester() } }
    val recentFocus = remember { FocusRequester() }
    LaunchedEffect(language) {
        repeat(4) {
            androidx.compose.runtime.withFrameNanos { }
            if (runCatching { firstKeyFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(24)
        }
    }
    Column(Modifier.fillMaxWidth().focusProperties { down = resultsFocus }) {
        Text(query.ifBlank { "Search the saved catalog" }, color = AppColors.Ink, style = TvType.title,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("tv-search-query"))
        if (recentSearches.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                recentSearches.forEach { term ->
                    Text(term, color = AppColors.Ink, style = TvType.caption,
                        modifier = Modifier.then(if (term == recentSearches.first()) Modifier.focusRequester(recentFocus) else Modifier)
                            .heightIn(min = 40.dp).tvFocusFrame().tvClickable { onRecentSelected(term) }
                            .padding(horizontal = 8.dp, vertical = 6.dp).testTag("recent-search"))
                }
                Text("Clear recents", color = AppColors.AccentText, style = TvType.caption,
                    modifier = Modifier.heightIn(min = 40.dp).tvFocusFrame().tvClickable(onClick = onClearRecents)
                        .padding(horizontal = 8.dp, vertical = 6.dp))
            }
        }
        keys.chunked(5).forEachIndexed { row, rowKeys ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowKeys.forEachIndexed { column, key ->
                    val index = row * 5 + column
                    val requester = requesters[index]
                    val links = remember(keys.size, index) {
                        listOf(-1 to tvSearchTarget(index, keys.size, 5, -1, 0),
                            1 to tvSearchTarget(index, keys.size, 5, 1, 0),
                            -2 to tvSearchTarget(index, keys.size, 5, 0, -1),
                            2 to tvSearchTarget(index, keys.size, 5, 0, 1))
                    }
                    Text(key.label, color = AppColors.Ink, style = TvType.label,
                        modifier = Modifier.weight(1f).width(1.dp).heightIn(min = 48.dp)
                            .background(AppColors.Elevated, RoundedCornerShape(8.dp))
                            .then(if (focusedIndex == index) Modifier.border(2.dp, AppColors.Accent, RoundedCornerShape(8.dp)) else Modifier)
                            .focusRequester(requester)
                            .focusProperties {
                                left = requesters[links.first { it.first == -1 }.second]
                                right = requesters[links.first { it.first == 1 }.second]
                                up = if (row == 0) {
                                    if (recentSearches.isNotEmpty()) recentFocus else FocusRequester.Cancel
                                } else requesters[links.first { it.first == -2 }.second]
                                down = if (row == (keys.lastIndex / 5)) resultsFocus else requesters[links.first { it.first == 2 }.second]
                            }
                            .onFocusChanged { if (it.isFocused) { focusedIndex = index; onKeyboardFocused() } }
                            .tvFocusFrame().tvClickable {
                                when (key.action) {
                                    TvSearchAction.Character, TvSearchAction.Space, TvSearchAction.Backspace -> onQueryChanged(editTvSearchQuery(query, key))
                                    TvSearchAction.Clear -> onQueryChanged("")
                                    TvSearchAction.SwitchLanguage -> { language = if (language == TvSearchLanguage.ENGLISH) TvSearchLanguage.ARABIC else TvSearchLanguage.ENGLISH; focusedIndex = 0 }
                                    TvSearchAction.Done -> onDone()
                                }
                            }.testTag("tv-search-key-$index"),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
