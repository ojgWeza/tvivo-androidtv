package com.dev.Tvivo.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.dev.Tvivo.data.local.entities.CategoryEntity
import com.dev.Tvivo.ui.theme.Palette

/**
 * Categories as the panel lists them. Inventing a curated hierarchy over ~120 arbitrary
 * panel names is guesswork; the filter solves navigation instead.
 *
 * Counts come from one grouped query exposed as a Flow, so they fill in live as the
 * background sync lands rather than appearing all at once at the end.
 */
@Composable
fun CategoryRail(
    categories: List<CategoryEntity>,
    counts: Map<String?, Int>,
    selectedCategoryId: String?,
    onSelect: (CategoryEntity) -> Unit,
    /** Where RIGHT goes. Without this, Compose's 2D focus search picks the header's
     *  Refresh — it is also to the right, and further from the rail than the first card
     *  only in a way the search does not weigh. The rail must reach the grid. */
    gridFocusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .width(360.dp)
            .fillMaxHeight()
            .background(Palette.Surface)
            .focusGroup(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 24.dp)
    ) {
        items(categories, key = { it.categoryId }) { category ->
            RailRow(
                name = category.name,
                count = counts[category.categoryId],
                selected = category.categoryId == selectedCategoryId,
                onSelect = { onSelect(category) },
                gridFocusRequester = gridFocusRequester
            )
        }
    }
}

@Composable
private fun RailRow(
    name: String,
    count: Int?,
    selected: Boolean,
    onSelect: () -> Unit,
    gridFocusRequester: FocusRequester
) {
    var focused by remember { mutableStateOf(false) }

    val background = when {
        focused -> Palette.Accent
        // Last-active state: when focus leaves the rail for the grid, the row the user
        // came from stays marked, or they lose their place entirely.
        selected -> Palette.Elevated
        else -> Palette.Surface
    }
    val textColor = if (focused) Palette.OnAccent else Palette.Ink

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The override belongs on the focused node itself: declared on the parent
            // focus group it is not consulted, and the 2D search picks the header's
            // Refresh instead — also to the right, and nothing weighs it as further.
            .focusProperties { right = gridFocusRequester }
            .background(background)
            .onFocusChanged { focused = it.isFocused }
            .clickable { onSelect() }
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = name,
            color = textColor,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = count?.toString() ?: "",
            color = if (focused) Palette.OnAccent else Palette.Dim,
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}
