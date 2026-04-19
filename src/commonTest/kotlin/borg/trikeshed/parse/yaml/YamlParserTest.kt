package borg.trikeshed.parse.yaml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YamlParserTest {
    @Test
    fun reifiesNestedMappingsAndSequences() {
        val result = YamlParser.reify(
            """
            openapi: 3.1.0
            info:
              title: Example API
              version: 1.0.0
            tags:
              - pets
              - orders
            paths:
              /pets:
                get:
                  operationId: listPets
            """.trimIndent(),
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
            servers:
              - url: https://api.example.com
                description: prod
              - url: https://sandbox.example.com
                description: sandbox
            """.trimIndent(),
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
            openapi: 3.1.0
            paths:
              /pets:
                get:
                  operationId: listPets
            """.trimIndent(),
        )

        assertTrue(document.root is YamlMappingNode)
        assertEquals(1, document.root.span.startLine)
        assertTrue(document.root.span.endLine >= document.root.span.startLine)
    }
}
