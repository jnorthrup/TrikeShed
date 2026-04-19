package borg.trikeshed.parse.interop

import borg.trikeshed.cursor.MapTypeMemento
import borg.trikeshed.cursor.SeqTypeMemento
import borg.trikeshed.lib.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DescriptorFragmentInteropTest {
    @Test
    fun jsonAndYamlProduceSameDescriptorSignature() {
        val json = """{"id":7,"tags":["a","b"],"info":{"title":"Example"}}"""
        val yaml =
            """
            id: 7
            tags:
              - "a"
              - "b"
            info:
              title: "Example"
            """.trimIndent()

        val jsonDescriptor = StructuredParserSupport.describeJsonText(json)
        val yamlDescriptor = StructuredParserSupport.describeYamlText(yaml)

        assertEquals(jsonDescriptor.signature(), yamlDescriptor.signature())
        assertEquals(MapTypeMemento, jsonDescriptor.memento)
        assertTrue(jsonDescriptor.children.any { it.key == "tags" && it.memento == SeqTypeMemento })
    }

    @Test
    fun ndjsonPreparedParserUsesSchemaCacheIdempotently() {
        val line1 = """{"id":1,"tags":["a"],"info":{"title":"A"}}"""
        val line2 = """{"id":2,"tags":["b"],"info":{"title":"B"}}"""
        val schemaIndex = mutableMapOf<String, NdjsonPreparedParser>()

        val first = StructuredParserSupport.prepareNdjsonParser(line1, schemaIndex)
        val second = StructuredParserSupport.prepareNdjsonParser(line2, schemaIndex)
        val parsed = StructuredParserSupport.parseNdjson(sequenceOf(line1, line2), schemaIndex).toList()

        assertEquals(first.descriptor.signature(), second.descriptor.signature())
        assertEquals(1, schemaIndex.size)
        assertEquals(2, parsed.size)
    }

    @Test
    fun ndjsonAndYamlExposeTreeCursorRows() {
        val line = """{"id":1,"tags":["a"],"info":{"title":"A"}}"""
        val yaml =
            """
            id: 1
            tags:
              - "a"
            info:
              title: "A"
            """.trimIndent()

        val ndjsonTree = StructuredParserSupport.prepareNdjsonParser(line, mutableMapOf()).describeRowTree(line)
        val yamlTree = StructuredParserSupport.describeYamlRowTree(yaml)
        val ndjsonRows = ndjsonTree.flatten().toList()
        val yamlRows = yamlTree.flatten().toList()

        assertEquals(ndjsonRows.size, yamlRows.size)
        assertEquals("($,0,{},Map)", ndjsonRows.first()[0].a)
        assertEquals("JsonConfix", ndjsonRows.first()[4].a)
        assertEquals("YamlIndent", yamlRows.first()[4].a)
        assertTrue(ndjsonRows.any { row -> row[9].a == "[]" })
    }

    @Test
    fun rawDescriptorsCarryOpaqueExtentFlavors() {
        val json = """{"id":1,"tags":["a"]}"""
        val yaml =
            """
            id: 1
            tags:
              - "a"
            """.trimIndent()

        val jsonDescriptor = StructuredParserSupport.describeJsonText(json)
        val yamlDescriptor = StructuredParserSupport.describeYamlText(yaml)

        val jsonExtent = jsonDescriptor.extent as JsonOpaqueExtent
        val yamlExtent = yamlDescriptor.extent as YamlOpaqueExtent

        assertEquals(ReificationFlavor.JsonConfix, jsonExtent.flavor)
        assertEquals(0, jsonExtent.startOffset)
        assertEquals(json.length - 1, jsonExtent.endOffsetInclusive)
        assertEquals(ReificationFlavor.YamlIndent, yamlExtent.flavor)
        assertEquals(1, yamlExtent.startLine)
        assertTrue(yamlExtent.endLine >= yamlExtent.startLine)
    }
}
