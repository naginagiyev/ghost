package app.termchat.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.termchat.AssistantItem
import app.termchat.EchoItem
import app.termchat.SystemItem
import app.termchat.UserItem
import app.termchat.data.ImageInfo
import app.termchat.engine.Tag
import kotlinx.coroutines.flow.StateFlow

private val finishedBlocks = LruCache<String, List<MdBlock>>(256)
private val blockGap = Arrangement.spacedBy(6.dp)
private val labelColumn = Regex("^\\S+ {2,}")

@Composable
fun SystemLine(item: SystemItem, gapAbove: Boolean) {
    val (label, color) = when (item.tag) {
        Tag.OK -> "[ ok ] " to Palette.user
        Tag.PENDING -> "[ .. ] " to Palette.dim
        Tag.WARN -> "[warn] " to Palette.warning
        Tag.FAIL -> "[fail] " to Palette.error
        Tag.INFO, Tag.DIM -> "" to Palette.dim
    }
    val textColor = when (item.tag) {
        Tag.INFO, Tag.WARN, Tag.FAIL -> Palette.text
        else -> Palette.dim
    }
    val hang = labelColumn.find(item.text)?.value?.length ?: 0
    val textStyle = BodyStyle.copy(color = textColor, textIndent = TextIndent(restLine = (hang * 0.6).em))
    Row(Modifier.padding(top = if (gapAbove) Spacing.block else 0.dp)) {
        if (label.isNotEmpty()) BasicText(label, style = BodyStyle.copy(color = color))
        BasicText(item.text, Modifier.weight(1f, fill = false), style = textStyle)
        if (item.tag == Tag.PENDING) {
            BasicText(" ", style = BodyStyle)
            Spinner(BodyStyle.copy(color = Palette.dim))
        }
    }
}

@Composable
fun EchoBlock(item: EchoItem) {
    Row(Modifier.padding(top = Spacing.block)) {
        BasicText("› ", style = BodyStyle.copy(color = Palette.user))
        BasicText(item.text, style = BodyStyle)
    }
}

@Composable
fun UserBlock(item: UserItem, thumbnail: suspend (String) -> Bitmap?) {
    Row(Modifier.padding(top = Spacing.block)) {
        BasicText("› ", style = BodyStyle.copy(color = Palette.user))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.tight)) {
            item.image?.let { ImageTag(it, thumbnail) }
            if (item.text.isNotEmpty()) BasicText(item.text, style = BodyStyle)
        }
    }
}

@Composable
private fun ImageTag(image: ImageInfo, thumbnail: suspend (String) -> Bitmap?) {
    BasicText(image.label, style = BodyStyle.copy(color = Palette.dim))
    val bitmap by produceState<Bitmap?>(null, image.path) { value = thumbnail(image.path) }
    bitmap?.let {
        Image(
            it.asImageBitmap(),
            contentDescription = image.label,
            modifier = Modifier
                .widthIn(max = 160.dp)
                .heightIn(max = 160.dp)
                .border(1.dp, Palette.border),
            contentScale = ContentScale.Fit,
            alignment = Alignment.TopStart,
        )
    }
}

@Composable
fun AssistantBlock(item: AssistantItem, onCopy: (String) -> Unit) {
    val blocks = remember(item.key) {
        finishedBlocks.get(item.key) ?: Markdown.parse(item.text).also { finishedBlocks.put(item.key, it) }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = Spacing.block)
            .combinedClickable(interactionSource = null, indication = null, onClick = {}, onLongClick = { onCopy(item.text) })
    ) {
        BasicText("◆ ", style = BodyStyle.copy(color = Palette.accent))
        Column(verticalArrangement = blockGap) {
            blocks.forEach { MarkdownBlock(it) }
            BasicText(item.footer, style = BodyStyle.copy(color = Palette.dim))
        }
    }
}

@Composable
fun StreamingBlock(stream: StateFlow<String>, startedAt: Long) {
    val text by stream.collectAsState()
    Row(Modifier.fillMaxWidth().padding(top = Spacing.block)) {
        BasicText("◆ ", style = BodyStyle.copy(color = Palette.accent))
        if (text.isEmpty()) {
            SpinnerWithElapsed(startedAt, BodyStyle.copy(color = Palette.dim))
        } else {
            StreamingText(text)
        }
    }
}

@Composable
private fun StreamingText(text: String) {
    val cache = remember { HashMap<Int, Pair<String, MdBlock>>() }
    val raw = Markdown.splitBlocks(text)
    val blink = rememberBlink()
    val layout = remember { mutableStateOf<TextLayoutResult?>(null) }
    Column(verticalArrangement = blockGap) {
        raw.forEachIndexed { index, source ->
            val isLast = index == raw.lastIndex
            val block = if (isLast) {
                Markdown.parseBlock(source)
            } else {
                cache[index]?.takeIf { it.first == source }?.second
                    ?: Markdown.parseBlock(source).also { cache[index] = source to it }
            }
            if (isLast) {
                MarkdownBlock(
                    block,
                    Modifier.blockCursor({ layout.value }, { block.text.length }, blink),
                    onTextLayout = { layout.value = it },
                )
            } else {
                MarkdownBlock(block)
            }
        }
    }
}

@Composable
private fun MarkdownBlock(
    block: MdBlock,
    modifier: Modifier = Modifier,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    val blockModifier = when (block) {
        is MdBlock.Code -> Modifier
            .fillMaxWidth()
            .background(Palette.codeBackground)
            .padding(vertical = Spacing.tight)
        is MdBlock.Text -> Modifier
    }
    BasicText(block.text, blockModifier.then(modifier), style = BodyStyle, onTextLayout = onTextLayout)
}
