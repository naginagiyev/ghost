package app.ghost.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ghost.AssistantItem
import app.ghost.Config
import app.ghost.EchoItem
import app.ghost.SessionState
import app.ghost.SystemItem
import app.ghost.UserItem
import app.ghost.engine.EngineState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Composable
fun TerminalScreen(
    state: SessionState,
    stream: StateFlow<String>,
    listState: LazyListState,
    thumbnail: suspend (String) -> Bitmap?,
    onStatusClick: () -> Unit,
    onSend: (String) -> Boolean,
    onStop: () -> Unit,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    onClearAttachment: () -> Unit,
    onConfirmDelete: (Boolean) -> Unit,
    onCopy: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        StatusLine(state, onStatusClick)
        Divider()
        Box(Modifier.weight(1f)) {
            Transcript(state, stream, listState, thumbnail, onCopy)
        }
        Divider()
        InputBar(
            busy = state.busy,
            streaming = state.streaming,
            attachment = state.attachment,
            pendingDelete = state.pendingDelete,
            onSend = onSend,
            onStop = onStop,
            onGallery = onGallery,
            onCamera = onCamera,
            onClearAttachment = onClearAttachment,
            onConfirmDelete = onConfirmDelete,
        )
    }
}

@Composable
private fun Transcript(
    state: SessionState,
    stream: StateFlow<String>,
    listState: LazyListState,
    thumbnail: suspend (String) -> Bitmap?,
    onCopy: (String) -> Unit,
) {
    var follow by remember { mutableStateOf(true) }
    val atBottom by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) follow = atBottom
        }
    }
    val count = state.items.size + if (state.streaming) 1 else 0
    LaunchedEffect(count) {
        if (follow) listState.scrollToItem(0)
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = Spacing.screen, vertical = Spacing.block),
        ) {
            if (state.streaming) {
                item(key = "stream", contentType = "stream") { StreamingBlock(stream, state.streamStartedAt) }
            }
            val reversed = state.items.asReversed()
            itemsIndexed(reversed, key = { _, item -> item.key }, contentType = { _, item -> item::class }) { index, item ->
                when (item) {
                    is SystemItem -> SystemLine(item, gapAbove = reversed.getOrNull(index + 1).let { it != null && it !is SystemItem })
                    is EchoItem -> EchoBlock(item)
                    is UserItem -> UserBlock(item, thumbnail)
                    is AssistantItem -> AssistantBlock(item, onCopy)
                }
            }
        }
        if (!atBottom) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(Spacing.screen)
                    .size(40.dp)
                    .background(Palette.codeBackground)
                    .clickable { scope.launch { listState.scrollToItem(0) } },
                contentAlignment = Alignment.Center,
            ) {
                BasicText("↓", style = BodyStyle.copy(color = Palette.accent))
            }
        }
    }
}

@Composable
private fun StatusLine(state: SessionState, onClick: () -> Unit) {
    val chat = state.chatId?.let { "chat-%02d".format(it) } ?: "new"
    val (dotColor, backend, word) = when (val engine = state.engine) {
        EngineState.Unloaded -> Triple(Palette.dim, "-", "unloaded")
        EngineState.Loading -> Triple(Palette.warning, "-", "loading")
        is EngineState.Ready -> Triple(Palette.user, engine.backend, "ready")
        is EngineState.Generating -> Triple(Palette.warning, engine.backend, "busy")
        is EngineState.Failed -> Triple(Palette.error, "-", "failed")
    }
    val context = "${compact(state.contextUsed)}/${compact(Config.CONTEXT_TOKENS)}"
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = Spacing.screen, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            "ghost ~ $chat",
            Modifier.weight(1f),
            style = SmallStyle.copy(color = Palette.text),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        BasicText("● ", style = SmallStyle.copy(color = dotColor))
        BasicText("${Config.MODEL_LABEL}  $backend  $word  ctx $context", style = SmallStyle, maxLines = 1)
    }
}

@Composable
private fun Divider() {
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(Palette.border))
}

private fun compact(tokens: Int): String =
    if (tokens < 1000) tokens.toString() else "%.1fk".format(tokens / 1024.0).replace(".0k", "k")
