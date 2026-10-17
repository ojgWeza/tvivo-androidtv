package com.dev.Tvivo.ui.browse

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.dev.Tvivo.home.CatalogSort
import com.dev.Tvivo.ui.common.HeaderIconButton
import com.dev.Tvivo.ui.common.sortIconVector
import com.dev.Tvivo.ui.theme.AppColors
import com.dev.Tvivo.ui.theme.TvType

internal fun catalogSortLabels(): List<String> = listOf("A–Z", "Z–A", "Recently added")

@Composable
fun CatalogSortMenu(
    selected: CatalogSort,
    onSelected: (CatalogSort) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        HeaderIconButton(sortIconVector(), "Sort items", onClick = { expanded = true },
            modifier = modifier.focusRequester(focusRequester).testTag("browse-sort"))
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.background(AppColors.Elevated)) {
            listOf(CatalogSort.AlphabeticalAsc, CatalogSort.AlphabeticalDesc,
                CatalogSort.RecentlyAdded).zip(catalogSortLabels()).forEach { (option, label) ->
                DropdownMenuItem(text = { Text(
                    text = if (option == selected) "✓ $label" else label,
                    color = AppColors.Ink,
                    style = TvType.label
                ) },
                    onClick = { expanded = false; onSelected(option) })
            }
        }
    }
}
