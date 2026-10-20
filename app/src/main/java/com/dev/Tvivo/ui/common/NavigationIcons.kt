package com.dev.Tvivo.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val LiveTvIcon: ImageVector = ImageVector.Builder(
    name = "Live TV", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 18f); lineTo(3f, 18f); close()
        moveTo(5f, 7f); lineTo(5f, 16f); lineTo(19f, 16f); lineTo(19f, 7f); close()
        moveTo(10f, 9f); lineTo(10f, 14f); lineTo(15f, 11.5f); close()
        moveTo(8f, 19f); lineTo(16f, 19f); lineTo(16f, 21f); lineTo(8f, 21f); close()
    }
}.build()

val CategoriesIcon: ImageVector = ImageVector.Builder(
    name = "Categories", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(3f, 3f); lineTo(10f, 3f); lineTo(10f, 10f); lineTo(3f, 10f); close()
        moveTo(14f, 3f); lineTo(21f, 3f); lineTo(21f, 10f); lineTo(14f, 10f); close()
        moveTo(3f, 14f); lineTo(10f, 14f); lineTo(10f, 21f); lineTo(3f, 21f); close()
        moveTo(14f, 14f); lineTo(21f, 14f); lineTo(21f, 21f); lineTo(14f, 21f); close()
    }
}.build()
