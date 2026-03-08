package com.simplexray.an.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.simplexray.an.ui.components.ScrollbarSettings

object ScrollbarDefaults {
    @Composable
    fun defaultScrollbarSettings(): ScrollbarSettings {
        return ScrollbarSettings(
            enabled = true,
            alwaysShowScrollbar = false,
            thumbThickness = 6.dp,
            scrollbarPadding = 0.dp,
            thumbMinLength = 0.1f,
            thumbMaxLength = 1.0f,
            thumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
            hideDelayMillis = 400,
            durationAnimationMillis = 500,
        )
    }
}
