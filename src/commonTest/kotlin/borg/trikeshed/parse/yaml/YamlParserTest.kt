package borg.trikeshed.parse.yaml

import borg.trikeshed.common.TypeEvidence
import borg.trikeshed.cursor.name
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YamlParserTest {
    @Test
    fun reifiesNestedMappingsAndSequences() {
        val result = YamlParser.reify(
            """
           |openapi: 3.1.0
           |info:
           |  title: Example API
           |  version: 1.0.0
           |tags:
           |  - pets
           |  - orders
           |paths:
           |  /pets:
           |    get:
           |      operationId: listPets
           |""".trimMargin( ),
        ) as Map<String, Any?>

        assertEquals("3.1.0", result["openapi"])
        val info = result["info"] as Map<String, Any?>
        assertEquals("Example API", info["title"])
        assertEquals(listOf("pets", "orders"), result["tags"])
        val paths = result["paths"] as Map<String, Any?>
        val pets = paths["/pets"] as Map<String, Any?>
        val get = pets["get"] as Map<String, Any?>
        assertEquals("listPets", get["operationId"])
    }

    @Test
    fun parsesSequenceOfMappings() {
        val result = YamlParser.reify(
            """
            |servers:
            |  - url: https://api.example.com
            |    description: prod
            |  - url: https://sandbox.example.com
            |    description: sandbox
            |""".trimMargin(    ),
        ) as Map<String, Any?>

        val servers = result["servers"] as List<Map<String, Any?>>
        assertEquals(2, servers.size)
        assertEquals("https://api.example.com", servers[0]["url"])
        assertEquals("sandbox", servers[1]["description"])
    }

    @Test
    fun exposesIndexedNodeFacade() {
        val document = YamlParser.parse(
            """
            |openapi: 3.1.0
            |paths:
            |  /pets:
            |    get:
            |      operationId: listPets
            |""".trimMargin(),
        )

        assertTrue(document.root is YamlMappingNode)
        assertEquals(1, document.root.span.startLine)
        assertTrue(document.root.span.endLine >= document.root.span.startLine)
    }

    @Test
    fun reifyCarriesOptionalEvidenceAndRowVecCallbacks() {
        val nodeEvidence = mutableListOf<TypeEvidence>()
        val rowVecs = mutableListOf<borg.trikeshed.cursor.RowVec>()

        val result = YamlParser.reify(
            """
            |openapi: 3.1.0
            |info:
            |  title: Example API
            |tags:
            |  - pets
            |  - orders
            |""".trimMargin(),
            nodeEvidence,
            rowVecs::add,
        )

        assertTrue(result is Map<*, *>)
        assertEquals(nodeEvidence.size, rowVecs.size)
        assertEquals("confix", rowVecs.first()[0].b().name)
        assertEquals("{}", rowVecs.first()[0].a)
        assertEquals("deducedType", rowVecs.first()[rowVecs.first().size - 1].b().name)
        assertTrue(nodeEvidence.any { it.confix == "[]" })
        assertTrue(nodeEvidence.any { it.dquotes > 0U || it.alpha > 0U || it.digits > 0U })
    }
}
