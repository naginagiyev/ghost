package app.ghost.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.ghost.data.ImageInfo

private const val MAX_INPUT_LINES = 6

@Composable
fun InputBar(
    busy: Boolean,
    streaming: Boolean,
    attachment: ImageInfo?,
    pendingDelete: Boolean,
    onSend: (String) -> Boolean,
    onStop: () -> Unit,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    onClearAttachment: () -> Unit,
    onConfirmDelete: (Boolean) -> Unit,
) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var focused by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    val blink = rememberBlink()
    val suggestions = remember(value.text) { Commands.suggestions(value.text) }
    val overflowing = (layout.value?.lineCount ?: 0) > MAX_INPUT_LINES
    val textColor = if (busy) Palette.dim else Palette.text

    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.screen)) {
        if (pendingDelete) {
            Row(Modifier.padding(top = Spacing.tight), verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    "delete this conversation?",
                    Modifier.weight(1f),
                    style = BodyStyle.copy(color = Palette.warning),
                )
                TermButton("delete", Palette.error) { onConfirmDelete(true) }
                TermButton("cancel", Palette.text) { onConfirmDelete(false) }
            }
        }
        attachment?.let {
            Row(Modifier.padding(top = Spacing.tight), verticalAlignment = Alignment.CenterVertically) {
                BasicText(it.label, style = BodyStyle.copy(color = Palette.dim))
                TermButton("remove", Palette.error, onClearAttachment)
            }
        }
        if (attachMenu) {
            Row(Modifier.padding(top = Spacing.tight), verticalAlignment = Alignment.CenterVertically) {
                BasicText("attach", Modifier.weight(1f), style = BodyStyle.copy(color = Palette.dim))
                TermButton("gallery", Palette.accent) {
                    attachMenu = false
                    onGallery()
                }
                TermButton("camera", Palette.accent) {
                    attachMenu = false
                    onCamera()
                }
            }
        }
        if (suggestions.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(top = Spacing.tight),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                suggestions.forEach { command ->
                    BasicText(
                        command.word,
                        Modifier.clickable {
                            value = TextFieldValue(command.word, TextRange(command.word.length))
                        }.padding(vertical = 6.dp),
                        style = BodyStyle.copy(color = Palette.dim),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            BasicText("› ", Modifier.padding(vertical = 10.dp), style = BodyStyle.copy(color = Palette.user))
            Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused }
                        .blockCursor(
                            { if (focused && !overflowing) layout.value else null },
                            { value.selection.end },
                            blink,
                        ),
                    textStyle = BodyStyle.copy(color = textColor),
                    cursorBrush = SolidColor(if (overflowing) Palette.accent else Color.Transparent),
                    maxLines = MAX_INPUT_LINES,
                    onTextLayout = { layout.value = it },
                )
            }
            Box(
                Modifier
                    .size(40.dp)
                    .clickable(interactionSource = null, indication = null) { attachMenu = !attachMenu },
                contentAlignment = Alignment.Center,
            ) {
                BasicText("+", style = BodyStyle.copy(color = if (attachMenu) Palette.accent else Palette.dim))
            }
            if (streaming || busy) {
                TermButton("stop", Palette.error, onStop)
            } else {
                TermButton("send", Palette.accent) {
                    if (onSend(value.text)) value = TextFieldValue("")
                }
            }
        }
    }
}

@Composable
fun TermButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText("[ $label ]", style = BodyStyle.copy(color = color))
    }
}
