package com.dev.Tvivo.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import com.dev.Tvivo.R
import com.dev.Tvivo.ui.theme.AppColors
import com.dev.Tvivo.ui.theme.TvType

@Composable
fun FileSizeLabel(size: FileSizeDisplay?, modifier: Modifier = Modifier) {
    size ?: return
    val approximate = size.isEstimate
    val description = stringResource(
        if (approximate) R.string.file_size_approximate_accessibility else R.string.file_size_accessibility,
        size.text
    )
    Text(
        stringResource(if (approximate) R.string.file_size_estimate else R.string.file_size, size.text),
        color = AppColors.Dim,
        style = TvType.label,
        modifier = modifier.semantics { contentDescription = description }
    )
}
