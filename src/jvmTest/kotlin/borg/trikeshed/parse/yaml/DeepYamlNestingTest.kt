package borg.trikeshed.parse.yaml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeepYamlNestingTest {
    @Test
    fun `deep nesting with properties leaks nothing`() {
        val yaml = """
openapi: 3.1.0
info:
  title: Test
  version: v1
components:
  schemas:
    Error:
      type: object
      additionalProperties: true
      properties:
        detail:
          type: string
        code:
          type: string
    TradingAccount:
      type: object
      properties:
        account_number:
          type: string
        status:
          type: string
paths:
  /v1/test:
    get:
      operationId: testGet
      responses:
        200:
          description: ok
  /v1/other:
    get:
      operationId: otherGet
      responses:
        200:
          description: ok
""".trimIndent() + "\n"

        val result = parse(yaml)
        println("top keys: ${result.keys}")
        val paths = result["paths"] as? Map<*, *>
        println("paths count: ${paths?.size}")
        val components = result["components"] as? Map<*, *>
        println("components schemas keys: ${(components?.get("schemas") as? Map<*, *>)?.keys}")
        assertEquals(setOf("openapi", "info", "components", "paths"), result.keys)
        assertEquals(2, paths?.size)
    }
}
