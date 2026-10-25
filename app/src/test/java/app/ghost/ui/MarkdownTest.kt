package app.ghost.ui

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
    fun headingDropsHashes() {
        val block = Markdown.parseBlock("## Title")
        assertEquals("Title", block.text.text)
        assertTrue(block.text.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun listsAndQuotesShowMarkers() {
        assertEquals("• item", Markdown.parseBlock("- item").text.text)
        assertEquals("☑ done", Markdown.parseBlock("- [x] done").text.text)
        assertEquals("1. first", Markdown.parseBlock("1. first").text.text)
        assertEquals("│ quoted", Markdown.parseBlock("> quoted").text.text)
    }

    @Test
    fun tableRowWithoutSeparatorStaysText() {
        val block = Markdown.parseBlock("| a | *b* |")
        assertTrue(block is MdBlock.Text)
        assertEquals("| a | b |", block.text.text)
    }

    @Test
    fun tableIsDrawnAsAlignedGrid() {
        val block = Markdown.parseBlock("| Name | Age |\n|:--|--:|\n| Sam | 30 |\n| **Al** | 7 |")
        assertTrue(block is MdBlock.Table)
        val expected = listOf(
            "┌──────┬─────┐",
            "│ Name │ Age │",
            "├──────┼─────┤",
            "│ Sam  │  30 │",
            "├╌╌╌╌╌╌┼╌╌╌╌╌┤",
            "│ Al   │   7 │",
            "└──────┴─────┘",
        ).joinToString("\n")
        assertEquals(expected, block.text.text)
    }

    @Test
    fun longTableCellsWrap() {
        val block = Markdown.parseBlock("| a | b |\n|---|---|\n| one two three four five six seven eight nine | x |")
        val lines = block.text.text.split('\n')
        assertTrue(lines.all { it.length <= 40 })
        assertTrue(lines.size > 5)
    }

    @Test
    fun paragraphAndTableSplitIntoBlocks() {
        val blocks = Markdown.splitBlocks("Here:\n| a | b |\n|---|---|\n| 1 | 2 |\nDone")
        assertEquals(listOf("Here:", "| a | b |\n|---|---|\n| 1 | 2 |", "Done"), blocks)
    }

    @Test
    fun latexBecomesReadableText() {
        assertEquals("speed = d/t", Latex.convert("\\text{speed} = \\frac{d}{t}"))
        assertEquals("E = mc²", Latex.convert("E = mc^2"))
        assertEquals("(a+b)/(2 × c)", Latex.convert("\\frac{a+b}{2 \\times c}"))
        assertEquals("x₁ ≤ √2 · π", Latex.convert("x_1 \\leq \\sqrt{2} \\cdot \\pi"))
        assertEquals("90°", Latex.convert("90^\\circ"))
    }

    @Test
    fun inlineMathIsConverted() {
        assertEquals("area is πr² here", Markdown.inline("area is \$\\pi r^2\$ here").text)
        assertEquals("so ½ of it", Markdown.inline("so \\(\\frac{1}{2}\\) of it").text)
        assertEquals("KE = ½mv²", Latex.convert("KE = \\frac{1}{2}mv^2"))
        assertEquals("total: 5 kg", Markdown.inline("total: \\text{5 kg}").text)
    }

    @Test
    fun moneyIsNotMath() {
        assertEquals("costs \$5 and \$10", Markdown.inline("costs \$5 and \$10").text)
    }

    @Test
    fun displayMathBlock() {
        val blocks = Markdown.parse("Formula:\n\n\$\$\na^2 + b^2 = c^2\n\$\$")
        assertEquals("  a² + b² = c²", blocks.last().text.text)
    }

    @Test
    fun linksEscapesStrikeAndRules() {
        assertEquals("see docs now", Markdown.inline("see [docs](https://x.y) now").text)
        assertEquals("a *star* and #tag", Markdown.inline("a \\*star\\* and \\#tag").text)
        assertEquals("old new", Markdown.inline("~~old~~ new").text)
        assertEquals("─".repeat(32), Markdown.parseBlock("---").text.text)
        assertEquals("line\nbreak", Markdown.inline("line<br>break").text)
    }
}
