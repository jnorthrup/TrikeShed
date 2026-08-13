package borg.trikeshed.reactor.openapi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class OpenApiRawParserTest {
    @Test
    fun rawParserExtractsOperationIdSchemaRef() {
        val spec = """
        {
            "openapi": "3.0.0",
            "paths": {
                "/widgets": {
                    "get": {
                        "operationId": "getWidgets",
                        "responses": {
                            "200": {
                                "content": {
                                    "application/json": {
                                        "schema": {
                                            "${'$'}ref": "#/components/schemas/Widget"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            "components": {
                "schemas": {
                    "Widget": {
                        "type": "object",
                        "properties": {
                            "id": { "type": "integer" }
                        }
                    }
                }
            }
        }
        """.trimIndent()

        val ops = OpenApiRawParser.parse(spec)

        val operations = ops.operations()
        assertEquals(1, operations.size)
        assertEquals("getWidgets", operations[0].operationId)

        val refs = ops.refs()
        assertEquals(1, refs.size)
        assertEquals("#/components/schemas/Widget", refs[0])
    }

    @Test
    fun rawParserRejectsOperationWithoutOperationId() {
        val spec = """
        openapi: 3.1.0
        info:
          title: Missing operation id
          version: v1
        paths:
          /health:
            get:
              responses:
                '200':
                  description: ok
        """.trimIndent()

        val gaps = OpenApiRawParser.parse(spec).gapAnalysis().gaps

        assertEquals("missing-operation-id", gaps.single().code)
    }
}
