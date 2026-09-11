package com.dev.Tvivo.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.tv.material3.Text
import com.dev.Tvivo.data.local.entities.CategoryEntity
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import com.dev.Tvivo.ui.common.dpadFieldNavigation
import com.dev.Tvivo.ui.common.tvClickable
import com.dev.Tvivo.ui.theme.Palette
import com.dev.Tvivo.ui.theme.TvType

/** D-3. Was 360 dp, which bought nothing: no panel name fits on one line at either
 *  width, so the extra 80 dp only narrowed the grid. */
private val RAIL_WIDTH = 280.dp

/** Fixed so the numbers never move as the sync fills them in. 96 px at 1 dp = 2 px. */
private val COUNT_COLUMN = 48.dp

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
    filter: String,
    onFilterChanged: (String) -> Unit,
    onSelect: (CategoryEntity) -> Unit,
    /** Where RIGHT goes. Without this, Compose's 2D focus search picks the header's
     *  Refresh — it is also to the right, and further from the rail than the first card
     *  only in a way the search does not weigh. The rail must reach the grid. */
    gridFocusRequester: FocusRequester,
    /** The screen's resting focus. Requested once categories arrive, so the first D-pad
     *  press has somewhere to move *from* — see [BrowseScreen]. */
    railFocusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val filterFocusRequester = remember { FocusRequester() }
    Column(
        modifier = modifier
            .width(RAIL_WIDTH)
            .fillMaxHeight()
            .background(Palette.Surface)
    ) {
        // D-13. Pinned above the list rather than scrolling with it: a filter that
        // scrolls out of view while you are looking at its results is a filter you
        // forget is applied.
        CategoryFilterField(
            value = filter,
            onValueChange = onFilterChanged,
            gridFocusRequester = gridFocusRequester,
            focusRequester = filterFocusRequester
        )

        LazyColumn(
            // Requesting focus on a focus *group* delegates to its first focusable
            // child, which is what makes this safe against a LazyColumn whose rows are
            // not composed yet. The same pattern the grid already uses.
            modifier = Modifier
                .fillMaxHeight()
                .focusRequester(railFocusRequester)
                .focusGroup(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)
        ) {
            itemsIndexed(categories, key = { _, it -> it.categoryId }) { index, category ->
                RailRow(
                    name = category.name,
                    count = counts[category.categoryId],
                    selected = category.categoryId == selectedCategoryId,
                    onSelect = { onSelect(category) },
                    gridFocusRequester = gridFocusRequester,
                    // Only the first row sends UP to the filter. Declared per-row rather
                    // than on the group, or UP from row 40 would jump to the filter
                    // instead of row 39.
                    filterFocusRequester = filterFocusRequester.takeIf { index == 0 }
                )
            }

            // Says why the rail is empty. Without this, filtering to nothing looks
            // identical to a category list that failed to load.
            if (categories.isEmpty() && filter.isNotBlank()) {
                item {
                    Text(
                        text = "No category matches \"$filter\"",
                        color = Palette.Dim,
                        style = TvType.label,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
                    )
                }
            }
        }
    }
}

/**
 * **Q-19 — this field trapped focus.** Without [dpadFieldNavigation] the text field eats
 * D-pad up/down for its own caret, so once focus lands here the only way out is Back:
 * the filtered categories the user just produced are unreachable, which makes the whole
 * feature unusable. Identical in shape to the original login focus trap.
 *
 * **Q-24 — and RIGHT has to reach the grid, exactly as it does from a category row.**
 * The rail is one pane; which row of it focus happens to be on must not change what
 * crossing to the content means. [RailRow] already carries this override and the field
 * did not, so RIGHT worked from every row of the rail except the one at the top of it.
 * The override is required rather than optional for the same reason it is on [RailRow]:
 * the 2D search scores the header's Refresh as a perfectly good candidate to the right.
 */
@Composable
private fun CategoryFilterField(
    value: String,
    onValueChange: (String) -> Unit,
    gridFocusRequester: FocusRequester,
    focusRequester: FocusRequester
) {
    val focusManager = LocalFocusManager.current
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        // `Filter`, not `Search`: it narrows a list that is already on screen. Search
        // is the thing that goes and finds rows you are not looking at, and this is not
        // that — promising it here would be promising the wrong feature.
        label = { Text("Filter categories", color = Palette.Dim, style = TvType.caption) },
        singleLine = true,
        textStyle = TvType.body,
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedTextColor = Palette.Ink,
            unfocusedTextColor = Palette.Ink,
            focusedContainerColor = Palette.Elevated,
            unfocusedContainerColor = Palette.Elevated,
            cursorColor = Palette.Accent,
            focusedBorderColor = Palette.Accent,
            unfocusedBorderColor = Palette.Line
        ),
        // `Next` rather than `Done`: the natural next move after typing a filter is
        // into the list it just produced.
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            autoCorrect = false,
            imeAction = androidx.compose.ui.text.input.ImeAction.Next
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
            onNext = { focusManager.moveFocus(FocusDirection.Down) }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .focusRequester(focusRequester)
            .focusProperties { right = gridFocusRequester }
            .dpadFieldNavigation(focusManager)
    )
}

@Composable
private fun RailRow(
    name: String,
    count: Int?,
    selected: Boolean,
    onSelect: () -> Unit,
    gridFocusRequester: FocusRequester,
    /** Non-null on the first row only: UP from the top of the list opens the filter. */
    filterFocusRequester: FocusRequester? = null
) {
    var focused by remember { mutableStateOf(false) }
    var overflowed by remember { mutableStateOf(false) }

    val background = when {
        focused -> Palette.Accent
        // Last-active state: when focus leaves the rail for the grid, the row the user
        // came from stays marked, or they lose their place entirely.
        selected -> Palette.Accent.copy(alpha = 0.40f)
        else -> Palette.Surface
    }
    val textColor = if (focused) Palette.OnAccent else Palette.Ink

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The override belongs on the focused node itself: declared on the parent
            // focus group it is not consulted, and the 2D search picks the header's
            // Refresh instead — also to the right, and nothing weighs it as further.
            .focusProperties {
                right = gridFocusRequester
                // **Q-25 — UP from the top of the list is how the filter is reached.**
                // The field is pinned above the list and is not part of it, so without
                // this the 2D search is free to leave the rail entirely and hand UP to
                // the grid, which is what it did.
                filterFocusRequester?.let { up = it }
            }
            .background(background)
            .onFocusChanged { focused = it.isFocused }
            .tvClickable { onSelect() }
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // **Never ellipsised.** This panel publishes `RAMADAN EGYPT 2026 SD` and
        // `RAMADAN EGYPT 2026 HD`, which truncate to the same string — ellipsising the
        // rail recreates exactly the false-duplicate defect Q-12 fixed in the grid.
        // Wrap to two lines instead, and only if a name still does not fit do we fall
        // back to showing it in full on focus.
        Text(
            text = name,
            color = textColor,
            style = TvType.title,
            maxLines = 2,
            onTextLayout = { overflowed = it.hasVisualOverflow },
            modifier = Modifier.weight(1f)
        )
        Text(
            // Blank, never `0`: `0` claims the category is empty, which is a different
            // and false statement while the sync is still running.
            text = count?.toString() ?: "",
            color = if (focused) Palette.OnAccent else Palette.Dim,
            style = TvType.caption,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier
                .padding(start = 12.dp)
                .width(COUNT_COLUMN)
        )
    }

    // Conditional, never unconditional: the tooltip appears only for the small number
    // of names that overflow even two lines, so it is a genuine signal rather than a
    // panel that pops up on every row the user passes through.
    if (focused && overflowed) {
        Popup(offset = IntOffset(x = 0, y = 0)) {
            Text(
                text = name,
                color = Palette.Ink,
                style = TvType.title,
                modifier = Modifier
                    .width(RAIL_WIDTH)
                    .background(Palette.Elevated)
                    .padding(horizontal = 24.dp, vertical = 14.dp)
            )
        }
    }
}
