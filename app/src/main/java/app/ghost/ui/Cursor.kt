package app.ghost.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.delay

private const val BLINK_MS = 530L
private const val SPINNER_FRAME_MS = 80L
private const val SPINNER_FRAMES = "▘▝▗▖"

@Composable
fun rememberBlink(): State<Boolean> {
    val visible = remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(BLINK_MS)
            visible.value = !visible.value
        }
    }
    return visible
}

fun Modifier.blockCursor(
    layout: () -> TextLayoutResult?,
    offset: () -> Int,
    visible: State<Boolean>,
    color: Color = Palette.accent,
): Modifier = drawWithContent {
    val result = layout()
    if (visible.value && result != null) {
        val position = offset().coerceIn(0, result.layoutInput.text.length)
        val rect = result.getCursorRect(position)
        val width = result.layoutInput.style.fontSize.toPx() * 0.6f
        drawRect(color, Offset(rect.left, rect.top), Size(width, rect.height))
    }
    drawContent()
}

@Composable
fun Spinner(style: TextStyle, modifier: Modifier = Modifier) {
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(SPINNER_FRAME_MS)
            frame = (frame + 1) % SPINNER_FRAMES.length
        }
    }
    BasicText(SPINNER_FRAMES[frame].toString(), modifier, style)
}

@Composable
fun SpinnerWithElapsed(startedAt: Long, style: TextStyle) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(startedAt) {
        while (true) {
            delay(SPINNER_FRAME_MS)
            frame = (frame + 1) % SPINNER_FRAMES.length
            now = System.currentTimeMillis()
        }
    }
    val seconds = (now - startedAt).coerceAtLeast(0) / 1000.0
    BasicText("${SPINNER_FRAMES[frame]} ${"%.1f".format(seconds)}s", style = style)
}
