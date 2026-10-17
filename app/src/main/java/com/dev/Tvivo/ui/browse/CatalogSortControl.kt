package com.dev.Tvivo.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.dev.Tvivo.home.CatalogSort
import com.dev.Tvivo.ui.common.tvClickable
import com.dev.Tvivo.ui.common.tvFocusFrame
import com.dev.Tvivo.ui.theme.AppColors
import com.dev.Tvivo.ui.theme.TvType

@Composable
internal fun CatalogSortControl(
    selected: CatalogSort,
    onSelect: (CatalogSort) -> Unit,
    options: List<CatalogSort>,
    scopeLabel: String,
    rowRequester: FocusRequester? = null,
    trailingAction: (@Composable () -> Unit)? = null
) {
    LazyRow(contentPadding = PaddingValues(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = (if (rowRequester != null) Modifier.focusRequester(rowRequester) else Modifier)
            .focusGroup()) {
        item(key = "scope") {
            Text(scopeLabel, color = AppColors.Dim, style = TvType.label,
                modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 12.dp))
        }
        items(options, key = { it.name }) { sort ->
            val label = when (sort) {
                CatalogSort.MostVisited -> "Most visited"
                CatalogSort.AlphabeticalAsc -> "A–Z"
                CatalogSort.AlphabeticalDesc -> "Z–A"
                CatalogSort.RecentlyAdded -> "Recently added"
            }
            Text(if (sort == selected) "✓ $label" else label,
                color = if (sort == selected) AppColors.AccentText else AppColors.Ink,
                style = TvType.label,
                modifier = Modifier.testTag("sort-${sort.name}").heightIn(min = 48.dp)
                    .tvFocusFrame().tvClickable { onSelect(sort) }
                    .padding(horizontal = 10.dp, vertical = 12.dp))
        }
        if (trailingAction != null) item(key = "trailing-action") { trailingAction() }
    }
}
