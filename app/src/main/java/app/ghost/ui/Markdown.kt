package app.ghost.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em

@Immutable
sealed interface MdBlock {
    val text: AnnotatedString

    data class Text(override val text: AnnotatedString) : MdBlock
    data class Code(override val text: AnnotatedString) : MdBlock
    data class Table(override val text: AnnotatedString) : MdBlock
}

private enum class Align { LEFT, CENTER, RIGHT }

object Markdown {
    private const val CHAR_WIDTH_EM = 0.6f
    private const val TABLE_CHARS = 40
    private const val MIN_COLUMN = 6
    private const val RULE_CHARS = 32
    private val heading = Regex("^(#{1,6})\\s+(.*)")
    private val bullet = Regex("^(\\s*)([-*+])\\s+(.*)")
    private val numbered = Regex("^(\\s*)(\\d+[.)])\\s+(.*)")
    private val quote = Regex("^>\\s?(.*)")
    private val rule = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val task = Regex("^\\[([ xX])]\\s+(.*)")
    private val separatorCell = Regex("^\\s*:?-{1,}:?\\s*$")
    private val lineBreak = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private const val ESCAPABLE = "\\`*_{}[]()#+-.!|~$<>"

    private val headingStyle = SpanStyle(color = Palette.accent, fontWeight = FontWeight.Bold)
    private val markerStyle = SpanStyle(color = Palette.accent)
    private val dimStyle = SpanStyle(color = Palette.dim)
    private val quoteStyle = SpanStyle(color = Palette.dim, fontStyle = FontStyle.Italic)
    private val inlineCodeStyle = SpanStyle(color = Palette.accent, background = Palette.codeBackground)
    private val mathStyle = SpanStyle(color = Palette.math)
    private val linkStyle = SpanStyle(color = Palette.accent, textDecoration = TextDecoration.Underline)
    private val headerCellStyle = SpanStyle(fontWeight = FontWeight.Bold, color = Palette.accent)

    fun parse(text: String): List<MdBlock> = splitBlocks(text).map(::parseBlock)

    fun splitBlocks(text: String): List<String> {
        val blocks = ArrayList<String>()
        val current = StringBuilder()
        var inFence = false
        var inMath = false
        var lastWasTable = false
        fun flush() {
            if (current.isNotEmpty()) blocks.add(current.toString())
            current.setLength(0)
        }
        for (line in text.split('\n')) {
            val trimmed = line.trim()
            val isFence = trimmed.startsWith("```")
            val isTable = trimmed.startsWith("|")
            val isMathEdge = trimmed == "$$" || trimmed == "\\[" || trimmed == "\\]"
            when {
                inFence -> {
                    current.append('\n').append(line)
                    if (isFence) {
                        inFence = false
                        flush()
                    }
                }
                inMath -> {
                    current.append('\n').append(line)
                    if (isMathEdge || trimmed.endsWith("$$") || trimmed.endsWith("\\]")) {
                        inMath = false
                        flush()
                    }
                }
                isFence -> {
                    flush()
                    current.append(line)
                    inFence = true
                }
                isMathEdge || (trimmed.startsWith("$$") && !trimmed.endsWith("$$")) || (trimmed.startsWith("\\[") && !trimmed.endsWith("\\]")) -> {
                    flush()
                    current.append(line)
                    inMath = true
                }
                line.isBlank() -> flush()
                else -> {
                    if (current.isNotEmpty() && isTable != lastWasTable) flush()
                    if (current.isNotEmpty()) current.append('\n')
                    current.append(line)
                }
            }
            if (!line.isBlank()) lastWasTable = isTable
        }
        flush()
        return blocks
    }

    fun parseBlock(raw: String): MdBlock {
        val lines = raw.split('\n')
        val first = lines.first().trim()
        if (first.startsWith("```")) return parseCode(lines)
        if (isTable(lines)) return parseTable(lines)
        if (first.startsWith("$$") || first.startsWith("\\[")) return parseMath(raw)
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
        bullet.matchEntire(line)?.let { return it.groupValues[1].length + 2 }
        numbered.matchEntire(line)?.let { return it.groupValues[1].length + it.groupValues[2].length + 1 }
        if (quote.matches(line)) return 2
        return 0
    }

    private fun AnnotatedString.Builder.appendLine(line: String) {
        heading.matchEntire(line)?.let {
            withStyle(headingStyle) { appendInline(it.groupValues[2]) }
            return
        }
        if (rule.matches(line)) {
            withStyle(dimStyle) { append("─".repeat(RULE_CHARS)) }
            return
        }
        bullet.matchEntire(line)?.let {
            val (spaces, _, rest) = it.destructured
            append(spaces)
            val checkbox = task.matchEntire(rest)
            if (checkbox != null) {
                withStyle(markerStyle) { append(if (checkbox.groupValues[1] == " ") "☐" else "☑") }
                append(' ')
                appendInline(checkbox.groupValues[2])
            } else {
                withStyle(markerStyle) { append("•") }
                append(' ')
                appendInline(rest)
            }
            return
        }
        numbered.matchEntire(line)?.let {
            val (spaces, marker, rest) = it.destructured
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

    private fun parseMath(raw: String): MdBlock {
        val body = raw.trim().removePrefix("$$").removePrefix("\\[").removeSuffix("$$").removeSuffix("\\]")
        return MdBlock.Text(buildAnnotatedString {
            body.split('\n').map(Latex::convert).filter { it.isNotEmpty() }.forEachIndexed { index, line ->
                if (index > 0) append('\n')
                append("  ")
                withStyle(mathStyle) { append(line) }
            }
        })
    }

    private fun isTable(lines: List<String>): Boolean =
        lines.size >= 2 && lines.all { it.trim().startsWith("|") } && cells(lines[1]).all { separatorCell.matches(it) }

    private fun cells(line: String): List<String> {
        val trimmed = line.trim().removePrefix("|").let { if (it.endsWith("|") && !it.endsWith("\\|")) it.dropLast(1) else it }
        val result = ArrayList<String>()
        val cell = StringBuilder()
        var i = 0
        while (i < trimmed.length) {
            val c = trimmed[i]
            if (c == '\\' && trimmed.getOrNull(i + 1) == '|') {
                cell.append('|')
                i += 2
                continue
            }
            if (c == '|') {
                result.add(cell.toString().trim())
                cell.setLength(0)
            } else {
                cell.append(c)
            }
            i++
        }
        result.add(cell.toString().trim())
        return result
    }

    private fun parseTable(lines: List<String>): MdBlock {
        val aligns = cells(lines[1]).map {
            val cell = it.trim()
            when {
                cell.startsWith(':') && cell.endsWith(':') -> Align.CENTER
                cell.endsWith(':') -> Align.RIGHT
                else -> Align.LEFT
            }
        }
        val rows = (listOf(lines[0]) + lines.drop(2)).map { line -> cells(line).map { inline(it.replace(lineBreak, "\n")) } }
        val columns = rows.maxOf { it.size }
        val natural = IntArray(columns) { column ->
            rows.maxOf { row -> row.getOrNull(column)?.text?.split('\n')?.maxOf { it.length } ?: 0 }.coerceAtLeast(1)
        }
        val widths = fitWidths(natural)
        val wrapped = rows.map { row -> List(columns) { column -> wrap(row.getOrNull(column) ?: AnnotatedString(""), widths[column]) } }
        return MdBlock.Table(buildAnnotatedString {
            border("┌", "┬", "┐", widths)
            wrapped.forEachIndexed { rowIndex, row ->
                append('\n')
                val height = row.maxOf { it.size }
                for (lineIndex in 0 until height) {
                    if (lineIndex > 0) append('\n')
                    withStyle(dimStyle) { append("│") }
                    row.forEachIndexed { column, cellLines ->
                        val part = cellLines.getOrNull(lineIndex) ?: AnnotatedString("")
                        val space = widths[column] - part.length
                        val left = when (aligns.getOrNull(column)) {
                            Align.RIGHT -> space
                            Align.CENTER -> space / 2
                            else -> 0
                        }
                        append(" ".repeat(left + 1))
                        if (rowIndex == 0) withStyle(headerCellStyle) { append(part) } else append(part)
                        append(" ".repeat(space - left + 1))
                        withStyle(dimStyle) { append("│") }
                    }
                }
                append('\n')
                when {
                    rowIndex == 0 && wrapped.size > 1 -> border("├", "┼", "┤", widths)
                    rowIndex == wrapped.lastIndex -> border("└", "┴", "┘", widths)
                    else -> border("├", "┼", "┤", widths, thin = true)
                }
            }
            if (wrapped.size == 1) {
                append('\n')
                border("└", "┴", "┘", widths)
            }
        })
    }

    private fun AnnotatedString.Builder.border(left: String, middle: String, right: String, widths: IntArray, thin: Boolean = false) {
        val line = if (thin) "╌" else "─"
        withStyle(dimStyle) { append(widths.joinToString(middle, left, right) { line.repeat(it + 2) }) }
    }

    private fun fitWidths(natural: IntArray): IntArray {
        val available = TABLE_CHARS - (3 * natural.size + 1)
        if (natural.sum() <= available) return natural
        val widths = IntArray(natural.size)
        var remaining = available
        var open = natural.size
        for (column in natural.indices.sortedBy { natural[it] }) {
            val share = maxOf(MIN_COLUMN, remaining / open)
            widths[column] = minOf(natural[column], share)
            remaining -= widths[column]
            open--
        }
        return widths
    }

    private fun wrap(cell: AnnotatedString, width: Int): List<AnnotatedString> {
        val result = ArrayList<AnnotatedString>()
        var start = 0
        for (piece in cell.text.split('\n')) {
            val end = start + piece.length
            var lineStart = start
            while (end - lineStart > width) {
                var cut = cell.text.lastIndexOf(' ', lineStart + width)
                if (cut <= lineStart) cut = lineStart + width
                result.add(cell.subSequence(lineStart, cut))
                lineStart = if (cut < end && cell.text[cut] == ' ') cut + 1 else cut
            }
            result.add(cell.subSequence(lineStart, end))
            start = end + 1
        }
        return result
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
            if (c == '\\' && i + 1 < text.length) {
                val next = text[i + 1]
                val closing = when (next) {
                    '(' -> "\\)"
                    '[' -> "\\]"
                    else -> null
                }
                if (closing != null) {
                    val end = text.indexOf(closing, i + 2)
                    if (end > i + 2) {
                        withStyle(mathStyle) { append(Latex.convert(text.substring(i + 2, end))) }
                        i = end + 2
                        continue
                    }
                }
                if (next.isLetter()) {
                    var nameEnd = i + 1
                    while (nameEnd < text.length && text[nameEnd].isLetter()) nameEnd++
                    if (Latex.isCommand(text.substring(i + 1, nameEnd))) {
                        val length = Latex.commandLength(text, i)
                        withStyle(mathStyle) { append(Latex.convert(text.substring(i, i + length))) }
                        i += length
                        continue
                    }
                } else if (next in ESCAPABLE) {
                    append(next)
                    i += 2
                    continue
                }
            }
            if (c == '$') {
                val double = text.startsWith("$$", i)
                val marker = if (double) "$$" else "$"
                val end = text.indexOf(marker, i + marker.length)
                if (end > i + marker.length) {
                    val body = text.substring(i + marker.length, end)
                    if (double || looksLikeMath(body, text.getOrNull(end + 1))) {
                        withStyle(mathStyle) { append(Latex.convert(body)) }
                        i = end + marker.length
                        continue
                    }
                }
            }
            if (c == '!' && text.startsWith("![", i)) {
                val link = link(text, i + 1)
                if (link != null) {
                    withStyle(dimStyle) { append("[image: ${link.first}]") }
                    i = link.second
                    continue
                }
            }
            if (c == '[') {
                val link = link(text, i)
                if (link != null) {
                    withStyle(linkStyle) { appendInline(link.first) }
                    i = link.second
                    continue
                }
            }
            if (text.startsWith("***", i)) {
                val end = text.indexOf("***", i + 3)
                if (end > i + 3) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                        appendInline(text.substring(i + 3, end))
                    }
                    i = end + 3
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
            if (text.startsWith("~~", i)) {
                val end = text.indexOf("~~", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInline(text.substring(i + 2, end)) }
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
            if (c == '<') {
                val match = lineBreak.matchAt(text, i)
                if (match != null) {
                    append('\n')
                    i += match.value.length
                    continue
                }
            }
            append(c)
            i++
        }
    }

    private fun link(text: String, start: Int): Pair<String, Int>? {
        val close = text.indexOf("](", start + 1)
        if (close < 0) return null
        val end = text.indexOf(')', close + 2)
        val label = text.substring(start + 1, close)
        if (end < 0 || label.any { it == '\n' || it == '[' || it == ']' }) return null
        return label to end + 1
    }

    private fun looksLikeMath(body: String, after: Char?): Boolean {
        if (body.first().isWhitespace() || body.last().isWhitespace()) return false
        if (after != null && after.isDigit()) return false
        if (body.any { it in "\\^_={}" }) return true
        return body.length <= 3 && body.any { it.isLetter() }
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
