package borg.trikeshed.lcnc.reactor

import borg.trikeshed.lcnc.isam.LcncDatabase
import borg.trikeshed.lcnc.isam.LcncPage
import borg.trikeshed.lcnc.isam.LcncWorkspace
import borg.trikeshed.lcnc.isam.LcncBlock
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LcncTaxonomyCodecTest {
    @Test
    fun jsonWorkspacePreservesHierarchyAndBlocks() {
        val result = LcncTaxonomyCodec.json(
            """{"kind":"workspace","id":"ws","name":"Forge","databases":[{"id":"db","title":"Cards","pages":[{"id":"p","title":"One","blocks":[{"id":"b","type":"paragraph","content":"hello"}]}]}]}"""
        )
        val workspace = assertIs<LcncWorkspace>(result[0])
        assertEquals("ws", workspace.id)
        assertEquals("Forge", workspace.name)
        val database = workspace.databases[0]
        assertEquals("db", database.id)
        assertEquals("ws", database.parentId)
        val page = database.pages[0]
        assertEquals("p", page.id)
        assertEquals("db", page.parentId)
        assertEquals("hello", page.contentBlocks[0].content)
    }

    @Test
    fun arbitraryJsonArrayBecomesDatabaseRowsWithoutDroppingFields() {
        val result = LcncTaxonomyCodec.json("[{\"id\":\"a\",\"title\":\"A\",\"status\":\"open\"}]")
        val database = assertIs<LcncDatabase>(result[0])
        assertEquals(1, database.pages.size)
        val property = assertIs<LcncBlock>(database.pages[0].contentBlocks[0])
        assertEquals("property", property.type)
        assertEquals(mapOf("name" to "status", "value" to "open"), property.content)
    }

    @Test
    fun nativeEntityWrapperAndHtmlUseRealContent() {
        val native = assertIs<LcncPage>(LcncTaxonomyCodec.native(
            """{"entity":{"kind":"page","id":"native-page","title":"Native","blocks":[{"type":"heading_1","text":"Heading"}]}}"""
        )[0])
        assertEquals("Heading", native.contentBlocks[0].content)

        val html = assertIs<LcncPage>(LcncTaxonomyCodec.html(
            "<html><head><title>Imported</title><style>hidden</style></head><body>" +
                "<h1>Heading &amp; More</h1><p>Visible <strong>text</strong>.</p>" +
                "<script>secret()</script></body></html>"
        )[0])
        assertEquals("Imported", html.title)
        assertTrue((0 until html.contentBlocks.size).none {
            html.contentBlocks[it].content == "hidden" || html.contentBlocks[it].content == "secret()"
        })
        assertEquals("Heading & More", html.contentBlocks[0].content)
        assertEquals("Visible text.", html.contentBlocks[1].content)
    }

    @Test
    fun malformedJsonIsNotAPlaceholderPage() {
        assertFailsWith<IllegalStateException> {
            LcncTaxonomyCodec.json("{\"items\":[}")
        }
    }

    @Test
    fun pipelineHonorsSupportedFormats() = runTest {
        val pipeline = LcncIngestPipeline(
            ingestId = "structured",
            supportedFormats = setOf(IngestFormat.JSON),
        )
        assertEquals(1, pipeline.decode(IngestSource.Paste("{\"title\":\"page\",\"text\":\"body\"}"), IngestFormat.JSON).size)
        assertEquals(0, pipeline.decode(IngestSource.Paste("<p>body</p>"), IngestFormat.HTML).size)
    }
}
