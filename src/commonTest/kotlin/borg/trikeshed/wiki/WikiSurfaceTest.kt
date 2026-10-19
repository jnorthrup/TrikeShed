package borg.trikeshed.wiki

import borg.trikeshed.lib.*
import kotlin.test.*

class WikiSurfaceTest {
    @Test
    fun relativeAndTitleLinksRetainCanonicalPaths() {
        val source = WikiArticle("patterns/source.md", """
            # Source
            [Retrieval](../skills/retrieval.md#Read first)
            [Legacy root](skills/retrieval.md)
            [[retrieval skill|Retrieve]]
            [[Café_notes]]
            [[Absent page]]
        """.trimIndent(), "source-cid")
        val pages = s_[source,
            WikiArticle("skills/retrieval.md", "# Retrieval skill\n## Read first", "retrieval-cid"),
            WikiArticle("patterns/Café notes.md", "# Café notes", "cafe-cid")]
        val html = WikiSurface.render(pages, source.path)
        assertContains(html, "href=\"/wiki?page=skills%2Fretrieval.md#Read_first\"")
        assertContains(html, "href=\"/wiki?page=skills%2Fretrieval.md\">Retrieve</a>")
        assertContains(html, "href=\"/wiki?page=patterns%2FCaf%C3%A9%20notes.md\"")
        assertContains(html, "class=\"wiki-missing\"")
        assertContains(html, "page=Absent%20page.md")
    }

    @Test
    fun onlyActualLinksContributeBacklinks() {
        val target = WikiArticle("target.md", "# Target\n## Read first\nText\n## Read first\n## content\n## wiki-backlinks", "target-cid")
        val quoted = WikiArticle("quoted.md", """
            # Quoted
            `[[Target]]` and `[Target](target.md)`
            ``[[Target]]``
            `multiline
            [[Target]]`
            ```markdown
            [[Target]]
            [Target](target.md)
            ```
            ~~~markdown
            [[Target]]
            ~~~
            > ```
            > [[Target]]
            > ```
        """.trimIndent(), "quoted-cid")
        val actual = WikiArticle("actual.md", "# Actual\n[[Target#Read first|Read]]", "actual-cid")
        val pages = s_[target, quoted, actual]
        val html = WikiSurface.render(pages, target.path)
        val backlinks = html.substringAfter("<section class=\"wiki-backlinks\"")
        assertContains(backlinks, "page=actual.md")
        assertFalse(backlinks.contains("page=quoted.md"))
        assertContains(html, "id=\"Read_first\"")
        assertContains(html, "id=\"Read_first_2\"")
        assertContains(html, "href=\"#Read_first_2\"")
        assertEquals(1, Regex("id=\"content\"").findAll(html).count())
        assertEquals(1, Regex("id=\"wiki-backlinks\"").findAll(html).count())
        assertEquals(0, WikiSurface.content(quoted, pages).links.size)
    }

    @Test
    fun untrustedMarkupAndSourceStayLiteral() {
        val source = WikiArticle("unsafe.md", """
            # Unsafe
            <script>alert(1)</script>
            [click](javascript:alert(1))
            [[Target|<img src=x onerror=alert(1)>]]
            `[click](https://example.org)`
            ```
            <img src=x onerror=alert(1)>
            ```
        """.trimIndent(), "<cid>")
        val pages = s_[source, WikiArticle("Target.md", "# Target", "target-cid")]
        val html = WikiSurface.render(pages, source.path)
        assertFalse(html.contains("href=\"javascript:"))
        assertFalse(html.contains("<img"))
        assertFalse(html.contains("<script>alert"))
        assertFalse(html.contains("href=\"https://example.org\""))
        assertContains(html, "&lt;script&gt;alert(1)&lt;/script&gt;")
        assertContains(html, "&lt;cid&gt;")
        val raw = WikiSurface.render(pages, source.path, source = true)
        assertContains(raw, "<pre class=\"wiki-source\"><code># Unsafe")
        assertContains(raw, "[[Target|&lt;img")
    }

    @Test
    fun appUrlsAndAttachmentsKeepTheirDestinations() {
        val source = WikiArticle("patterns/source.md", """
            # Source
            [Documents](/documents#curation)
            [Trace](/api/cas/get?cid=sha256:abc&raw=1)
            [Summary source](/api/wiki/read?path=patterns%2Fconstellation-law.md)
            [Data](../data/evidence.json)
        """.trimIndent(), "cid")
        val html = WikiSurface.render(s_[source], source.path)
        assertContains(html, "href=\"/documents#curation\">Documents")
        assertContains(html, "href=\"/api/cas/get?cid=sha256:abc&amp;raw=1\"")
        assertContains(html, "href=\"/api/wiki/read?path=patterns%2Fconstellation-law.md\"")
        assertContains(html, "href=\"/api/wiki/read?path=data%2Fevidence.json\"")
    }

    @Test
    fun escapedCurationTitlesAndLabelsDisplayLiteralPunctuation() {
        val title = "Law [Act] (1900) *notes* #"
        val escaped = """Law \[Act\] \(1900\) \*notes\* \#"""
        val book = WikiArticle("library/book/index.md", "# $escaped\n\nRetained metadata.", "book", WikiKind.BOOK)
        val library = WikiArticle("library/index.md", "# Library\n[$escaped](/library%2Fbook%2Findex.md)", "library", WikiKind.LIBRARY)
        val pages = s_[library, book]
        assertEquals(title, WikiSurface.title(book))
        val index = WikiSurface.render(pages, library.path)
        assertContains(index, "href=\"/wiki?page=library%2Fbook%2Findex.md\">$title</a>")
        val html = WikiSurface.render(pages, book.path)
        assertContains(html, "<h1 class=\"wiki-title\">$title</h1>")
        assertFalse(html.contains("<h1 id="), "The literal title should not repeat inside the article")
        assertEquals("a \\* b", WikiSurface.plain("`a \\* b`"), "Code keeps literal backslashes")

        val source = WikiArticle("source.md", """[A\](B)](target\(part\).md)""", "source")
        val target = WikiArticle("target(part).md", "# Target", "target")
        assertContains(WikiSurface.render(s_[source, target], source.path),
            "href=\"/wiki?page=target%28part%29.md\">A](B)</a>")
    }

    @Test
    fun homepageListsConstellationsAndAuthoredPagesWithBooksBelow() {
        val library = WikiArticle("library/index.md", "# Library", "library", WikiKind.LIBRARY)
        val constellation = WikiArticle("patterns/constellation-law.md",
            "# Constellation law\n[Book](/library%2Fbook%2Findex.md)", "constellation", WikiKind.CONSTELLATION)
        val book = WikiArticle("library/book/index.md",
            "# Book\n[Section](0.md)", "book", WikiKind.BOOK)
        val section = WikiArticle("library/book/0.md", "# Section", "section", WikiKind.SECTION)
        val authored = WikiArticle("patterns/retrieval.md", "# Retrieval", "authored")
        val pages = s_[library, constellation, book, section, authored]
        val home = WikiSurface.render(pages)
        assertContains(home, "href=\"${WikiSurface.url(constellation.path)}\"")
        assertContains(home, "href=\"${WikiSurface.url(authored.path)}\"")
        for (nested in s_[library, book, section].view) {
            assertFalse(home.contains("href=\"${WikiSurface.url(nested.path)}\""), "${nested.path} is reached through its parent")
        }
        assertContains(WikiSurface.render(pages, constellation.path), "href=\"${WikiSurface.url(book.path)}\"")
        assertContains(WikiSurface.render(pages, book.path), "href=\"${WikiSurface.url(section.path)}\"")
        assertContains(WikiSurface.render(s_[library]), "href=\"${WikiSurface.url(library.path)}\"")
    }

    @Test
    fun incompleteAndMissingPagesAreExplicit() {
        val empty = emptyArray<WikiArticle>().toSeries()
        assertContains(WikiSurface.render(empty), "No wiki pages are available yet.")
        assertContains(WikiSurface.render(empty, "missing.md", truncated = true), "This page index is incomplete.")
        assertContains(WikiSurface.render(empty, unavailable = "plane offline"), "Wiki unavailable: plane offline")
        val duplicate = s_[WikiArticle("one/Topic.md", "# Topic", "a"), WikiArticle("two/Topic.md", "# Topic", "b")]
        assertNull(WikiSurface.resolve("topic", WikiArticle("index.md", "# Index", "index"), duplicate)?.article)
    }
}
