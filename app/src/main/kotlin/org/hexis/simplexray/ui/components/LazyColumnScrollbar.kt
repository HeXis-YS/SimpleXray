package org.hexis.simplexray.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.max

data class ScrollbarSettings(
    val enabled: Boolean = true,
    val alwaysShowScrollbar: Boolean = false,
    val thumbThickness: Dp = 6.dp,
    val scrollbarPadding: Dp = 0.dp,
    val thumbMinLength: Float = 0.1f,
    val thumbMaxLength: Float = 1.0f,
    val thumbColor: Color = Color.Black.copy(alpha = 0.15f),
    val hideDelayMillis: Int = 400,
    val durationAnimationMillis: Int = 300,
)

@Composable
fun LazyColumnScrollbar(
    state: LazyListState,
    settings: ScrollbarSettings,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier) {
        content()
        if (!settings.enabled) return@Box

        val info = state.layoutInfo
        if (info.totalItemsCount == 0 || info.visibleItemsInfo.isEmpty()) return@Box

        var show by remember { mutableStateOf(settings.alwaysShowScrollbar) }
        LaunchedEffect(settings.alwaysShowScrollbar, state.isScrollInProgress) {
            if (settings.alwaysShowScrollbar) {
                show = true
            } else if (state.isScrollInProgress) {
                show = true
            } else {
                delay(settings.hideDelayMillis.toLong())
                if (!state.isScrollInProgress) show = false
            }
        }

        val alpha by animateFloatAsState(
            targetValue = if (show) 1f else 0f,
            animationSpec = tween(settings.durationAnimationMillis),
            label = "scrollbar_alpha"
        )
        if (alpha <= 0f) return@Box

        val avgItemPx = info.visibleItemsInfo.sumOf { it.size }.toFloat() / info.visibleItemsInfo.size
        val viewportPx = (info.viewportEndOffset - info.viewportStartOffset).toFloat().coerceAtLeast(1f)
        val contentPx = max(viewportPx, avgItemPx * info.totalItemsCount)
        val thumbRatio = (viewportPx / contentPx).coerceIn(settings.thumbMinLength, settings.thumbMaxLength)
        val thumbHeight = viewportPx * thumbRatio
        val scrollablePx = max(contentPx - viewportPx, 1f)
        val currentScrollPx = state.firstVisibleItemIndex * avgItemPx + state.firstVisibleItemScrollOffset
        val topRatio = (currentScrollPx / scrollablePx).coerceIn(0f, 1f)
        val topOffset = topRatio * max(viewportPx - thumbHeight, 0f)

        val density = LocalDensity.current
        val thicknessPx = with(density) { settings.thumbThickness.toPx() }
        val paddingPx = with(density) { settings.scrollbarPadding.toPx() }
        val barWidth = settings.thumbThickness + settings.scrollbarPadding * 2

        Canvas(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(barWidth)
        ) {
            drawRoundRect(
                color = settings.thumbColor.copy(alpha = settings.thumbColor.alpha * alpha),
                topLeft = Offset(x = size.width - thicknessPx - paddingPx, y = topOffset),
                size = Size(width = thicknessPx, height = thumbHeight),
                cornerRadius = CornerRadius(thicknessPx / 2f, thicknessPx / 2f)
            )
        }
    }
}
