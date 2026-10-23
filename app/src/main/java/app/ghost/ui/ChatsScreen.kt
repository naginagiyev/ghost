package app.ghost.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ghost.data.ChatRow
import kotlinx.coroutines.flow.Flow

private enum class RowMode { OPTIONS, RENAME, DELETE }

@Composable
fun ChatsScreen(
    chats: Flow<List<ChatRow>>,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onPin: (Long, Boolean) -> Unit,
    onNew: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val rows by chats.collectAsState(initial = emptyList())
    var selected by remember { mutableStateOf<Long?>(null) }
    var mode by remember { mutableStateOf(RowMode.OPTIONS) }
    val now = System.currentTimeMillis()
    val pinned = rows.filter { it.pinned }
    val recent = rows.filterNot { it.pinned }
    val close = { selected = null }

    fun LazyListScope.section(label: String?, list: List<ChatRow>, offset: Int) {
        if (label != null && list.isNotEmpty()) {
            item(key = "label-$label") {
                BasicText(label, Modifier.padding(top = Spacing.block), style = SmallStyle)
            }
        }
        itemsIndexed(list, key = { _, row -> row.id }) { index, row ->
            if (selected == row.id) {
                when (mode) {
                    RowMode.OPTIONS -> OptionsRow(
                        row = row,
                        onPin = {
                            onPin(row.id, !row.pinned)
                            close()
                        },
                        onRename = { mode = RowMode.RENAME },
                        onDelete = { mode = RowMode.DELETE },
                        onCancel = close,
                    )
                    RowMode.RENAME -> RenameRow(
                        title = row.title,
                        onSave = {
                            onRename(row.id, it)
                            close()
                        },
                        onCancel = close,
                    )
                    RowMode.DELETE -> DeleteRow(
                        title = row.title,
                        onDelete = {
                            onDelete(row.id)
                            close()
                        },
                        onCancel = close,
                    )
                }
            } else {
                ChatLine(
                    number = offset + index + 1,
                    row = row,
                    ago = ago(now - row.updatedAt),
                    onClick = { onOpen(row.id) },
                    onLongClick = {
                        selected = row.id
                        mode = RowMode.OPTIONS
                    },
                )
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.screen, vertical = Spacing.block),
    ) {
        item(key = "header") {
            Column(Modifier.padding(bottom = Spacing.tight)) {
                BasicText("~/chats  (${rows.size})", style = BodyStyle.copy(color = Palette.accent))
                BasicText("tap to open · hold for pin, rename, delete", style = SmallStyle)
            }
        }
        section(if (pinned.isNotEmpty()) "pinned" else null, pinned, 0)
        section(if (pinned.isNotEmpty()) "recent" else null, recent, pinned.size)
        item(key = "new") {
            BasicText(
                "  +  new",
                Modifier
                    .fillMaxWidth()
                    .clickable(interactionSource = null, indication = null, onClick = onNew)
                    .padding(vertical = 12.dp),
                style = BodyStyle.copy(color = Palette.user),
            )
        }
    }
}

@Composable
private fun ChatLine(number: Int, row: ChatRow, ago: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = null,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(vertical = 8.dp),
    ) {
        BasicText(" %02d  ".format(number), style = BodyStyle.copy(color = Palette.dim))
        BasicText(
            row.title,
            Modifier.weight(1f),
            style = BodyStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        BasicText(
            ago.padStart(9) + "%4d msgs".format(row.messageCount),
            style = BodyStyle.copy(color = Palette.dim),
        )
    }
}

@Composable
private fun OptionsRow(row: ChatRow, onPin: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        BasicText(
            row.title,
            style = BodyStyle.copy(color = Palette.accent),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TermButton(if (row.pinned) "unpin" else "pin", Palette.accent, onPin)
            TermButton("rename", Palette.accent, onRename)
            TermButton("delete", Palette.error, onDelete)
            TermButton("cancel", Palette.dim, onCancel)
        }
    }
}

@Composable
private fun RenameRow(title: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(title, TextRange(title.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        BasicText("new title", style = SmallStyle)
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.tight)
                .border(1.dp, Palette.border)
                .padding(8.dp)
                .focusRequester(focus),
            textStyle = BodyStyle,
            cursorBrush = SolidColor(Palette.accent),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave(value.text) }),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TermButton("save", Palette.accent) { onSave(value.text) }
            TermButton("cancel", Palette.dim, onCancel)
        }
    }
}

@Composable
private fun DeleteRow(title: String, onDelete: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        BasicText(
            "delete \"$title\"?",
            style = BodyStyle.copy(color = Palette.warning),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        BasicText("messages and images in it are removed for good", style = SmallStyle)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TermButton("delete", Palette.error, onDelete)
            TermButton("cancel", Palette.text, onCancel)
        }
    }
}

private fun ago(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 24 * 60 -> "${minutes / 60}h ago"
        else -> "${minutes / (24 * 60)}d ago"
    }
}
