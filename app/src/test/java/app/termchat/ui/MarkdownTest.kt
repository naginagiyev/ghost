package app.termchat.ui

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    @Test
    fun splitsBlocksOnBlankLines() {
        val blocks = Markdown.splitBlocks("one\ntwo\n\nthree\n\n\nfour")
        assertEquals(listOf("one\ntwo", "three", "four"), blocks)
    }

    @Test
    fun keepsFencedCodeAsOneBlockWithBlankLines() {
        val blocks = Markdown.splitBlocks("intro\n```kotlin\nval a = 1\n\nval b = 2\n```\nafter")
        assertEquals(listOf("intro", "```kotlin\nval a = 1\n\nval b = 2\n```", "after"), blocks)
    }

    @Test
    fun unclosedFenceIsLastBlockWhileStreaming() {
        val blocks = Markdown.splitBlocks("text\n\n```\ncode line")
        assertEquals(listOf("text", "```\ncode line"), blocks)
        assertTrue(Markdown.parseBlock(blocks[1]) is MdBlock.Code)
    }

    @Test
    fun codeBlockDropsFencesAndPrefixesBar() {
        val block = Markdown.parseBlock("```python\nprint(1)\n```")
        assertTrue(block is MdBlock.Code)
        assertEquals("│ python\n│ print(1)", block.text.text)
    }

    @Test
    fun boldItalicAndInlineCodeRemoveMarkers() {
        val text = Markdown.inline("a **bold** and *it* and `code`")
        assertEquals("a bold and it and code", text.text)
        val bold = text.spanStyles.first { it.item.fontWeight == FontWeight.Bold }
        assertEquals("bold", text.text.substring(bold.start, bold.end))
        val italic = text.spanStyles.first { it.item.fontStyle == FontStyle.Italic }
        assertEquals("it", text.text.substring(italic.start, italic.end))
    }

    @Test
    fun snakeCaseIsNotItalic() {
        assertEquals("my_var_name and 2 * 3 * 4", Markdown.inline("my_var_name and 2 * 3 * 4").text)
    }

    @Test
    fun unclosedMarkersStayLiteral() {
        assertEquals("a **half", Markdown.inline("a **half").text)
        assertEquals("tick ` alone", Markdown.inline("tick ` alone").text)
    }

    @Test
    fun headingKeepsHashPrefix() {
        val block = Markdown.parseBlock("## Title")
        assertEquals("## Title", block.text.text)
        assertTrue(block.text.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun listsAndQuotesKeepMarkers() {
        assertEquals("- item", Markdown.parseBlock("- item").text.text)
        assertEquals("1. first", Markdown.parseBlock("1. first").text.text)
        assertEquals("│ quoted", Markdown.parseBlock("> quoted").text.text)
    }

    @Test
    fun tableRowsStayPlain() {
        assertEquals("| a | *b* |", Markdown.parseBlock("| a | *b* |").text.text)
    }
}
