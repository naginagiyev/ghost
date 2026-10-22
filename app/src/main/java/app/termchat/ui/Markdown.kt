package app.termchat.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em

@Immutable
sealed interface MdBlock {
    val text: AnnotatedString

    data class Text(override val text: AnnotatedString) : MdBlock
    data class Code(override val text: AnnotatedString) : MdBlock
}

object Markdown {
    private const val CHAR_WIDTH_EM = 0.6f
    private val heading = Regex("^#{1,6}\\s.*")
    private val bullet = Regex("^(\\s*)([-*+])\\s+(.*)")
    private val numbered = Regex("^(\\s*)(\\d+[.)])\\s+(.*)")
    private val quote = Regex("^>\\s?(.*)")

    private val headingStyle = SpanStyle(color = Palette.accent, fontWeight = FontWeight.Bold)
    private val markerStyle = SpanStyle(color = Palette.accent)
    private val dimStyle = SpanStyle(color = Palette.dim)
    private val quoteStyle = SpanStyle(color = Palette.dim, fontStyle = FontStyle.Italic)
    private val inlineCodeStyle = SpanStyle(color = Palette.accent, background = Palette.codeBackground)

    fun parse(text: String): List<MdBlock> = splitBlocks(text).map(::parseBlock)

    fun splitBlocks(text: String): List<String> {
        val blocks = ArrayList<String>()
        val current = StringBuilder()
        var inFence = false
        fun flush() {
            if (current.isNotEmpty()) blocks.add(current.toString())
            current.setLength(0)
        }
        for (line in text.split('\n')) {
            val isFence = line.trimStart().startsWith("```")
            when {
                inFence -> {
                    current.append('\n').append(line)
                    if (isFence) {
                        inFence = false
                        flush()
                    }
                }
                isFence -> {
                    flush()
                    current.append(line)
                    inFence = true
                }
                line.isBlank() -> flush()
                else -> {
                    if (current.isNotEmpty()) current.append('\n')
                    current.append(line)
                }
            }
        }
        flush()
        return blocks
    }

    fun parseBlock(raw: String): MdBlock {
        val lines = raw.split('\n')
        if (lines.first().trimStart().startsWith("```")) return parseCode(lines)
        return MdBlock.Text(buildAnnotatedString {
            for (line in lines) {
                val indent = paragraphIndent(line)
                withStyle(ParagraphStyle(textIndent = TextIndent(restLine = (indent * CHAR_WIDTH_EM).em))) {
                    appendLine(line)
                }
            }
        })
    }

    private fun paragraphIndent(line: String): Int {
        bullet.matchEntire(line)?.let { return it.groupValues[1].length + it.groupValues[2].length + 1 }
        numbered.matchEntire(line)?.let { return it.groupValues[1].length + it.groupValues[2].length + 1 }
        if (quote.matches(line)) return 2
        return 0
    }

    private fun AnnotatedString.Builder.appendLine(line: String) {
        if (heading.matches(line)) {
            withStyle(headingStyle) { append(line) }
            return
        }
        val listMatch = bullet.matchEntire(line) ?: numbered.matchEntire(line)
        if (listMatch != null) {
            val (spaces, marker, rest) = listMatch.destructured
            append(spaces)
            withStyle(markerStyle) { append(marker) }
            append(' ')
            appendInline(rest)
            return
        }
        quote.matchEntire(line)?.let {
            withStyle(dimStyle) { append("│ ") }
            withStyle(quoteStyle) { appendInline(it.groupValues[1]) }
            return
        }
        if (line.trimStart().startsWith("|")) {
            append(line)
            return
        }
        appendInline(line)
    }

    private fun parseCode(lines: List<String>): MdBlock {
        val language = lines.first().trimStart().removePrefix("```").trim()
        val closed = lines.size > 1 && lines.last().trimStart().startsWith("```")
        val body = lines.subList(1, if (closed) lines.size - 1 else lines.size)
        return MdBlock.Code(buildAnnotatedString {
            val all = if (language.isNotEmpty()) listOf(language) + body else body
            all.forEachIndexed { index, line ->
                if (index > 0) append('\n')
                withStyle(dimStyle) { append("│ ") }
                if (index == 0 && language.isNotEmpty()) withStyle(dimStyle) { append(line) } else append(line)
            }
        })
    }

    fun inline(text: String): AnnotatedString = buildAnnotatedString { appendInline(text) }

    private fun AnnotatedString.Builder.appendInline(text: String) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    withStyle(inlineCodeStyle) { append(text, i + 1, end) }
                    i = end + 1
                    continue
                }
            }
            if (text.startsWith("**", i)) {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(text.substring(i + 2, end)) }
                    i = end + 2
                    continue
                }
            }
            if ((c == '*' || c == '_') && opensItalic(text, i)) {
                val end = closingItalic(text, i)
                if (end > 0) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(text.substring(i + 1, end)) }
                    i = end + 1
                    continue
                }
            }
            append(c)
            i++
        }
    }

    private fun opensItalic(text: String, index: Int): Boolean {
        val next = text.getOrNull(index + 1) ?: return false
        if (next.isWhitespace() || next == text[index]) return false
        val previous = text.getOrNull(index - 1)
        return !(text[index] == '_' && previous != null && previous.isLetterOrDigit())
    }

    private fun closingItalic(text: String, start: Int): Int {
        val marker = text[start]
        var i = text.indexOf(marker, start + 1)
        while (i > 0) {
            val previous = text[i - 1]
            val next = text.getOrNull(i + 1)
            val validEnd = !previous.isWhitespace() && next != marker &&
                !(marker == '_' && next != null && next.isLetterOrDigit())
            if (validEnd) return i
            i = text.indexOf(marker, i + 1)
        }
        return -1
    }
}
