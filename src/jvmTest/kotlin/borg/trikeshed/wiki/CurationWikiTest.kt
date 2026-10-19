package borg.trikeshed.wiki

import borg.trikeshed.forge.server.WikiReadWire
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.narsese.ConstellationNodes
import borg.trikeshed.parse.jsonOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.*

class CurationWikiTest {
    @TempDir lateinit var directory: Path

    @Test fun retainedSectionsLinkWithoutCreatingMissingReadings() = runBlocking {
        val nodes = ConstellationNodes(directory.toFile(), ConfixBlackboard(), scope = this)
        try {
            val name = "Café 100%/book"
            val safe = name.replace(Regex("[^A-Za-z0-9._-]+"), "-")
            File(nodes.root, "law.members").writeText("$name\n")
            File(nodes.root, "books/$safe.json").writeText(jsonOf(mapOf(
                "name" to name, "work" to "Dictionary", "date" to "1900", "headings" to listOf("First", "Second"),
                "statements" to emptyList<Any>(), "support" to emptyList<Any>(), "cites" to listOf(listOf(0, 1)),
            )))
            File(nodes.root, "books/$safe.sections.jsonl").writeText(listOf("<script>alert(1)</script>\n[[Injected]]", "Retained second source")
                .joinToString("\n") { jsonOf(it) })
            val reading = nodes.readingFile(name, 0).apply { parentFile.mkdirs() }
            reading.writeText(jsonOf(listOf(mapOf(
                "text" to "A bank is an organization.",
                "concepts" to listOf(mapOf("lemma" to "bank", "class" to "Organization", "is" to listOf("Agent"))),
                "statements" to listOf(mapOf("bearer" to "bank", "force" to "is", "action" to "organization")),
            ))))
            val before = directory.toFile().walkTopDown().filter { it.isFile }.associate { it.relativeTo(directory.toFile()).path to it.readBytes().toList() }
            val wiki = CurationWiki(nodes)
            val firstPath = CurationWiki.sectionPath(name, 0)
            val secondPath = CurationWiki.sectionPath(name, 1)
            val pages = wiki.pages(firstPath)
            assertEquals(5, pages.size)
            assertEquals(WikiKind.LIBRARY, pages[0].kind)
            val first = pages.view.single { it.path == firstPath }
            val second = pages.view.single { it.path == secondPath }
            assertContains(first.text, "A bank is an organization.")
            assertFalse(second.text.contains("Retained second source"), "Unselected sections are catalog entries")
            val html = WikiSurface.render(pages, firstPath)
            assertContains(html, WikiSurface.url(secondPath))
            assertContains(html, WikiSurface.url(CurationWiki.bookPath(name)))
            assertContains(html, "href=\"#Concept_1\"")
            assertContains(html, "id=\"Class_1\"")
            assertFalse(html.contains("<script>alert"))
            assertFalse(html.contains("page=Injected"))
            assertContains(html, "&lt;script&gt;alert(1)&lt;/script&gt;")
            val missingReading = wiki.pages(secondPath).view.single { it.path == secondPath }
            assertContains(missingReading.text, "No retained sentence reading is available")
            assertContains(missingReading.text, "Retained second source")
            assertFalse(nodes.readingFile(name, 1).exists())
            val after = directory.toFile().walkTopDown().filter { it.isFile }.associate { it.relativeTo(directory.toFile()).path to it.readBytes().toList() }
            assertEquals(before, after, "Reading the wiki must not modify retained records")
        } finally { nodes.drain() }
    }

    @Test fun malformedSelectedReadingIsNotAnEmptySuccess() = runBlocking {
        val nodes = ConstellationNodes(directory.toFile(), ConfixBlackboard(), scope = this)
        try {
            File(nodes.root, "law.members").writeText("book\n")
            File(nodes.root, "books/book.json").writeText("""{"name":"book","work":"Fixture","date":"1900","headings":["First"],"statements":[],"support":[]}""")
            nodes.readingFile("book", 0).apply { parentFile.mkdirs(); writeText("{}") }
            assertFailsWith<IllegalArgumentException> { CurationWiki(nodes).pages(CurationWiki.sectionPath("book", 0)) }
            val wire = WikiReadWire(curations = { nodes }, wikiRoot = { directory.resolve("wiki").toFile() })
            val response = assertNotNull(wire.route("GET", WikiSurface.url(CurationWiki.sectionPath("book", 0)), ByteArray(0)))
            assertEquals(500, response.status)
            assertContains(response.body, "The retained library could not be read")
        } finally { nodes.drain() }
    }

    @Test fun libraryRoutesRemainAvailableWithoutPhysicalWikiFiles() = runBlocking {
        val nodes = ConstellationNodes(directory.toFile(), ConfixBlackboard(), scope = this)
        try {
            val name = "Café %/book"
            val safe = name.replace(Regex("[^A-Za-z0-9._-]+"), "-")
            File(nodes.root, "law.members").writeText("$name\n")
            File(nodes.root, "books/$safe.json").writeText(jsonOf(mapOf(
                "name" to name, "work" to "Fixture", "date" to "1900", "headings" to listOf("First"),
                "statements" to emptyList<Any>(), "support" to emptyList<Any>(),
            )))
            File(nodes.root, "books/$safe.sections.jsonl").writeText(jsonOf("Retained evidence"))
            val physicalWiki = directory.resolve("wiki").toFile()
            val wire = WikiReadWire(curations = { nodes }, wikiRoot = { physicalWiki })
            val index = assertNotNull(wire.route("GET", "/wiki", ByteArray(0)))
            assertEquals(200, index.status)
            assertContains(index.body, WikiSurface.url(CurationWiki.constellationPath("law")))
            val section = assertNotNull(wire.route("GET", WikiSurface.url(CurationWiki.sectionPath(name, 0)), ByteArray(0)))
            assertEquals(200, section.status)
            assertContains(section.body, "Retained evidence")
            assertContains(section.body, "No retained sentence reading is available")
            assertFalse(physicalWiki.exists())
            assertFalse(nodes.readingFile(name, 0).exists())
        } finally { nodes.drain() }
    }

    @Test fun constellationProjectionConsumesOnlyItsExactSavedPage() = runBlocking {
        val nodes = ConstellationNodes(directory.toFile(), ConfixBlackboard(), scope = this)
        try {
            File(nodes.root, "law.members").writeText("book\n")
            File(nodes.root, "books/book.json").writeText("""{"name":"book","work":"Fixture","date":"1900","headings":["First"],"statements":[],"support":[]}""")
            val path = CurationWiki.constellationPath("law")
            assertEquals("patterns/constellation-law.md", path)
            val summary = "# Constellation law\n\nRetained summary with an authored addition.\n"
            val physicalWiki = directory.resolve("wiki").toFile()
            val sourceFile = File(physicalWiki, path).apply { parentFile.mkdirs(); writeText(summary) }
            val sourceBytes = sourceFile.readBytes()
            val saved = WikiArticle(path, summary, ContentId.of(sourceBytes).value)
            val unrelated = WikiArticle("patterns/independent.md", "# Constellation law\n\nA separate authored pattern.\n", "independent-cid")
            val skill = WikiArticle("skills/example/SKILL.md", "# Example skill\n\nUnique instructions.\n", "skill-cid")
            val pages = CurationWiki(nodes).pages(path, s_[saved, unrelated, skill])
            val projected = pages.view.single { it.path == path }
            assertNotEquals(WikiKind.FILE, projected.kind)
            assertContains(projected.text, "Retained summary with an authored addition.")
            assertEquals(unrelated, pages.view.single { it.path == unrelated.path }, "Equal titles do not establish identity")
            assertEquals(skill, pages.view.single { it.path == skill.path })
            val links = WikiSurface.content(projected, pages).links.view.toSet()
            assertTrue(CurationWiki.ROOT in links)
            assertTrue(CurationWiki.bookPath("book") in links)
            val html = WikiSurface.render(pages, path)
            assertFalse(html.contains("<h1 id="), "The saved summary must not repeat the constellation title")
            assertContains(html, "href=\"/api/wiki/read?path=${WikiSurface.encode(path)}\"")
            val wire = WikiReadWire(curations = { nodes }, wikiRoot = { physicalWiki })
            val response = assertNotNull(wire.route("GET", "/api/wiki/read?path=${WikiSurface.encode(path)}", ByteArray(0)))
            assertEquals(200, response.status)
            assertContentEquals(sourceBytes, response.payloadBytes)
            assertContentEquals(sourceBytes, sourceFile.readBytes())
        } finally { nodes.drain() }
    }

    @Test fun sharedBooksProjectOnceAndLinkEveryMembership() = runBlocking {
        val nodes = ConstellationNodes(directory.toFile(), ConfixBlackboard(), scope = this)
        try {
            File(nodes.root, "law.members").writeText("book\n")
            File(nodes.root, "history.members").writeText("book\nbook\n")
            File(nodes.root, "books/book.json").writeText("""{"name":"book","work":"Fixture","date":"1900","headings":["First"],"statements":[],"support":[]}""")
            val pages = CurationWiki(nodes).pages(null)
            val bookPath = CurationWiki.bookPath("book")
            val sectionPath = CurationWiki.sectionPath("book", 0)
            val law = CurationWiki.constellationPath("law")
            val history = CurationWiki.constellationPath("history")
            assertEquals(5, pages.size)
            assertEquals(1, pages.view.count { it.path == bookPath })
            assertEquals(1, pages.view.count { it.path == sectionPath })
            fun links(path: String) = WikiSurface.content(pages.view.single { it.path == path }, pages).links.view.toSet()
            assertTrue(setOf(law, history).all { it in links(CurationWiki.ROOT) })
            assertTrue(bookPath in links(law))
            assertTrue(bookPath in links(history))
            assertTrue(setOf(law, history, sectionPath).all { it in links(bookPath) })
            assertTrue(bookPath in links(sectionPath))
            val bookBacklinks = WikiSurface.render(pages, bookPath).substringAfter("<section class=\"wiki-backlinks\"")
            assertContains(bookBacklinks, WikiSurface.url(law))
            assertContains(bookBacklinks, WikiSurface.url(history))
            val sectionBacklinks = WikiSurface.render(pages, sectionPath).substringAfter("<section class=\"wiki-backlinks\"")
            assertContains(sectionBacklinks, WikiSurface.url(bookPath))
        } finally { nodes.drain() }
    }
}
