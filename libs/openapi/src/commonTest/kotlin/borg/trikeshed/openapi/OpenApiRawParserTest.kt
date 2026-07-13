package borg.trikeshed.openapi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenApiRawParserTest {
    @Test
    fun parsesRawOperationsAndRefs() {
        val document = OpenApiRawParser.parse(
            """
            {
              "openapi": "3.1.0",
              "info": { "title": "Spec", "version": "1.0.0" },
              "paths": {
                "/pets": {
                  "get": {
                    "operationId": "listPets",
                    "responses": {
                      "200": {
                        "description": "ok",
                        "content": {
                          "application/json": {
                            "schema": { "${'$'}ref": "#/components/schemas/PetList" }
                          }
                        }
                      }
                    }
                  }
                }
              },
              "components": {
                "schemas": {
                  "PetList": { "type": "array" }
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals("3.1.0", document.version)
        assertEquals(1, document.operations().size)
        assertEquals("listPets", document.operations().single().operationId)
        assertEquals(listOf("#/components/schemas/PetList"), document.refs())
    }

    @Test
    fun rejectsNonOpenApiPayloads() {
        assertFailsWith<IllegalArgumentException> {
            OpenApiRawParser.parse("""{"paths":{}}""")
        }
    }

    @Test
    fun tokenizesAndGapAnalyzesIncompleteDocuments() {
        val document = OpenApiRawParser.parse(
            """
            {
              "openapi": "3.1.0",
              "info": { "title": "Spec" },
              "paths": {
                "/pets": {
                  "get": {
                    "responses": {
                      "200": {
                        "content": {
                          "application/json": {
                            "schema": { "${'$'}ref": "#/components/schemas/Missing" }
                          }
                        }
                      }
                    }
                  },
                  "post": {}
                }
              },
              "components": {
                "schemas": {
                  "Pet": { "type": "object" }
                }
              }
            }
            """.trimIndent(),
        )

        val analysis = document.gapAnalysis()

        assertTrue(analysis.tokens.any { it.kind == "version" && it.value == "3.1.0" })
        assertTrue(analysis.tokens.any { it.kind == "path" && it.value == "/pets" })
        assertTrue(analysis.tokens.any { it.kind == "ref" && it.value == "#/components/schemas/Missing" })
        assertTrue(analysis.gaps.any { it.code == "missing-info-version" })
        assertTrue(analysis.gaps.any { it.code == "missing-operation-id" && it.location.endsWith("get.operationId") })
        assertTrue(analysis.gaps.any { it.code == "missing-responses" && it.location.endsWith("post.responses") })
        assertTrue(analysis.gaps.any { it.code == "unresolved-ref" && it.location == "#/components/schemas/Missing" })
    }
}
