package io.github.uposum.romstore.core.net

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class HtmlTest {
    @Test
    fun numericReferencesThatAreNoCharacterBecomeTheReplacementCharacter() {
        assertEquals("a�b", Html.unescape("a&#x110000;b"))
        assertEquals("a�b", Html.unescape("a&#xD800;b"))
        assertEquals("a�b", Html.unescape("a&#99999999999999999999;b"))
        assertEquals("é & 😀", Html.unescape("&#233; &amp; &#x1F600;"))
        assertEquals("&bogus;", Html.unescape("&bogus;"))
    }

    /** The old blocks(): findMatchingClose from the end of every opening tag. */
    private fun reference(html: String, tag: String, needle: String?): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val pattern = Regex("<${Regex.escape(tag)}\\b[^>]*>", RegexOption.IGNORE_CASE)
        for (m in pattern.findAllFast(html)) {
            if (needle != null && needle !in m.value) continue
            val end = Html.findMatchingClose(html, tag, m.range.last + 1)
            if (end == -1) continue
            out += m.value to html.substring(m.range.first, end)
        }
        return out
    }

    @Test
    fun blocksAreTheBalancedElements() {
        val html = """<div class="a"><div class="b">x</div><DIV class="b">y<div>z</div></div ></div><div class="b">open"""
        assertEquals(
            listOf(
                """<div class="b">""" to """<div class="b">x</div>""",
                """<DIV class="b">""" to """<DIV class="b">y<div>z</div></div >""",
            ),
            Html.blocks(html, "div", "class=\"b\"").toList(),
        )
        assertEquals(reference(html, "div", null), Html.blocks(html).toList())
    }

    @Test
    fun blocksMatchTheOldScanOnAnyMarkup() {
        val pieces = listOf(
            "<div>", "<div class=\"x\">", "</div>", "</div >", "<DIV>", "</DIV>", "<divx>", "<div", ">", "text",
            "<span>", "</span>", "<div </div>", "<a>", "\n",
        )
        val random = Random(7)
        repeat(3000) {
            val html = buildString { repeat(random.nextInt(0, 30)) { append(pieces[random.nextInt(pieces.size)]) } }
            for (needle in listOf(null, "class=\"x\"")) {
                assertEquals(html, reference(html, "div", needle), Html.blocks(html, "div", needle).toList())
            }
        }
    }
}
