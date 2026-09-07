package borg.trikeshed.docs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The muggle renderer, pinned on every target the bundle and the daemon share. */
class DocumentMarkdownTest {

    @Test
    fun headingsParagraphsAndRules() {
        val html = DocumentMarkdown.render("# Alpha\n\nfirst note\nsecond line\n\n---\n\n## Beta ##\n")
        assertEquals("<h1>Alpha</h1>\n<p>first note second line</p>\n<hr>\n<h2>Beta</h2>\n", html)
    }

    @Test
    fun listsAndQuotes() {
        val html = DocumentMarkdown.render("- one\n- two\n\n1. first\n2) second\n\n> quoted\n> lines\n")
        assertEquals("<ul>\n<li>one</li>\n<li>two</li>\n</ul>\n<ol>\n<li>first</li>\n<li>second</li>\n</ol>\n<blockquote><p>quoted lines</p>\n</blockquote>\n", html)
    }

    @Test
    fun fencedCodeIsLiteralAndEscaped() {
        val html = DocumentMarkdown.render("```kotlin\nval x = a < b && *not* emphasis\n```\nafter")
        assertEquals("<pre><code class=\"language-kotlin\">val x = a &lt; b &amp;&amp; *not* emphasis\n</code></pre>\n<p>after</p>\n", html)
    }

    /**
     * The fence seam (AutoTools, Cut B): a caller may own a fenced block by its info string. The
     * handler receives the RAW body — the renderer escapes as it accumulates, so the two buffers
     * are kept apart — and whatever it returns replaces the `<pre><code>` block. A quoted fence is
     * NOT offered: the handler is not passed into the blockquote recursion.
     */
    @Test
    fun aFenceHandlerMayOwnABlockByItsInfoString() {
        val page = "before\n\n```lcnc-run\nval a = 1 < 2\n```\n\n```kotlin\nx\n```\n\n> ```lcnc-run\n> quoted\n> ```\n"
        val seen = ArrayList<Pair<String, String>>()
        val html = DocumentMarkdown.render(page) { info, body ->
            seen.add(info to body)
            if (info == "lcnc-run") "<section>owned</section>" else null
        }
        assertEquals(listOf("lcnc-run" to "val a = 1 < 2\n", "kotlin" to "x\n"), seen)
        assertTrue(html.contains("<section>owned</section>"), html)
        assertTrue(html.contains("<pre><code class=\"language-kotlin\">x\n</code></pre>"), html)
        assertTrue(html.contains("<blockquote><pre><code class=\"language-lcnc-run\">quoted\n</code></pre>\n</blockquote>"), html)
    }

    @Test
    fun inlineMarkupAndSafeLinks() {
        assertEquals("a <code>&lt;b&gt;</code> <strong>bold</strong> <em>em</em> <a href=\"https://x.test/p\">link</a>", DocumentMarkdown.inline("a `<b>` **bold** *em* [link](https://x.test/p)"))
        assertEquals("<a href=\"./b.md\">next</a>", DocumentMarkdown.inline("[next](./b.md)"))
        assertEquals("run (javascript:alert(1))", DocumentMarkdown.inline("[run](javascript:alert(1))"))
        assertTrue(DocumentMarkdown.safeTarget("mailto:a@b.c"))
        assertFalse(DocumentMarkdown.safeTarget("data:text/html,x"))
    }

    @Test
    fun rawHtmlIsTextNeverMarkup() {
        val html = DocumentMarkdown.render("<script>alert(1)</script> and snake_case_words stay")
        assertEquals("<p>&lt;script&gt;alert(1)&lt;/script&gt; and snake_case_words stay</p>\n", html)
    }
}
