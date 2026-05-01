package borg.trikeshed.openapi

import borg.trikeshed.parse.yaml.parse
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import java.io.File

class YamlParserTest {
    @Test
    fun `parse simple openapi spec`() {
        val spec = """
            |openapi: 3.1.0
            |info:
            |  title: Test API
            |  version: "1.0"
            |paths:
            |  /test:
            |    get:
            |      operationId: testOp
            |      responses:
            |        "200":
            |          description: OK
            |""".trimMargin()

        val result = parse(spec)
        assertNotNull(result, "parsed result should not be null")
        assertNotNull(result["openapi"], "openapi key should exist")
        assertNotNull(result["paths"], "paths key should exist")
    }

    @Test
    fun `parse coinmarketcap spec`() {
        val file = File("../../libs/cmc/endpoint-overview/openapi/coinmarketcap.openapi.yaml")
        if (file.exists()) {
            val specText = file.readText()

            val result = parse(specText)
            assertNotNull(result, "parsed result should not be null")
            val v = result["openapi"]
            println("openapi value: $v")
            val paths = result["paths"]
            println("paths value: $paths")
            assertNotNull(paths, "paths key should exist")
        }
    }
}
