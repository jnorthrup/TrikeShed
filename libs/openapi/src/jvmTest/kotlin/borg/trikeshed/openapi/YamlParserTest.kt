package borg.trikeshed.openapi

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull

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

        val result = Yaml.parse(spec)
        assertNotNull(result, "parsed result should not be null")
        assertIs<Map<String, Any?>>(result, "root should be a map")
        assertNotNull(result["openapi"], "openapi key should exist")
        assertNotNull(result["paths"], "paths key should exist")
        assertIs<Map<*, *>>(result["paths"], "paths should be a map")
    }

    @Test
    fun `parse coinmarketcap spec`() {
        val specText = java.io.File(
            "/Users/jim/work/TrikeShed/libs/cmc/endpoint-overview/openapi/coinmarketcap.openapi.yaml"
        ).readText()

        val result = Yaml.parse(specText)
        assertNotNull(result, "parsed result should not be null")
        assertIs<Map<String, Any?>>(result, "root should be a map")
        val v = result["openapi"]
        println("openapi value: $v")
        val paths = result["paths"]
        println("paths value: $paths")
        val pathsType = paths?.javaClass?.name
        println("paths type: $pathsType")
        assertNotNull(paths, "paths key should exist")
        assertIs<Map<*, *>>(paths, "paths should be a map")
    }
}
