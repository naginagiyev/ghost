package app.termchat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.termchat.data.ChatRow
import kotlinx.coroutines.flow.Flow

@Composable
fun ChatsScreen(
    chats: Flow<List<ChatRow>>,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onNew: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val rows by chats.collectAsState(initial = emptyList())
    var confirming by remember { mutableStateOf<Long?>(null) }
    val now = System.currentTimeMillis()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.screen, vertical = Spacing.block),
    ) {
        item(key = "header") {
            BasicText(
                "~/chats  (${rows.size})",
                Modifier.padding(bottom = Spacing.block),
                style = BodyStyle.copy(color = Palette.accent),
            )
        }
        itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
            if (confirming == row.id) {
                ConfirmRow(
                    title = row.title,
                    onYes = {
                        confirming = null
                        onDelete(row.id)
                    },
                    onNo = { confirming = null },
                )
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            interactionSource = null,
                            indication = null,
                            onClick = { onOpen(row.id) },
                            onLongClick = { confirming = row.id },
                        )
                        .padding(vertical = 8.dp),
                ) {
                    BasicText(" %02d  ".format(index + 1), style = BodyStyle.copy(color = Palette.dim))
                    BasicText(
                        row.title,
                        Modifier.weight(1f),
                        style = BodyStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    BasicText(
                        ago(now - row.updatedAt).padStart(9) + "%4d msgs".format(row.messageCount),
                        style = BodyStyle.copy(color = Palette.dim),
                    )
                }
            }
        }
        item(key = "new") {
            BasicText(
                "  +  new",
                Modifier
                    .fillMaxWidth()
                    .clickable(interactionSource = null, indication = null, onClick = onNew)
                    .padding(vertical = 8.dp),
                style = BodyStyle.copy(color = Palette.user),
            )
        }
    }
}

@Composable
private fun ConfirmRow(title: String, onYes: () -> Unit, onNo: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        BasicText(
            "rm \"$title\"? ",
            Modifier.weight(1f, fill = false),
            style = BodyStyle.copy(color = Palette.warning),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        BasicText(
            "[y",
            Modifier.clickable(interactionSource = null, indication = null, onClick = onYes),
            style = BodyStyle.copy(color = Palette.error),
        )
        BasicText("/", style = BodyStyle.copy(color = Palette.dim))
        BasicText(
            "N]",
            Modifier.clickable(interactionSource = null, indication = null, onClick = onNo),
            style = BodyStyle.copy(color = Palette.text),
        )
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
