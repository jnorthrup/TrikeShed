package borg.trikeshed.openapi

import borg.trikeshed.parse.yaml.parse
import kotlin.test.Test
import kotlin.test.assertTrue

class RobinhoodSpecParseTest {
    @Test
    fun `parse robinhood openapi spec`() {
        val specFile = java.io.File("/Users/jim/work/TrikeShed/libs/rhood/robinhood.openapi.yaml")
        val yaml = specFile.readText()
        val result = parse(yaml)
        println("top keys: ${result.keys}")
        val components = result["components"] as? Map<*, *>
        println("components keys: ${components?.keys}")
        println("components.securitySchemes: ${components?.get("securitySchemes")}")
        val paths = result["paths"] as? Map<*, *>
        println("paths count: ${paths?.size}")
        assertTrue(paths != null, "paths should exist, got top keys: ${result.keys}")
        assertTrue(paths.isNotEmpty(), "paths should not be empty")
    }
}
