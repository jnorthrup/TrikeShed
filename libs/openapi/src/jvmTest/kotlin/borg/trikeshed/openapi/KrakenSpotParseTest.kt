package borg.trikeshed.openapi

import borg.trikeshed.parse.yaml.parse
import kotlin.test.Test
import kotlin.test.assertTrue

class KrakenSpotParseTest {
    @Test
    fun `parse normalized kraken spot spec`() {
        val specFile = java.io.File("/Users/jim/work/TrikeShed/libs/krak/generated/openapi/kraken-spot.openapi.yaml")
        val yaml = specFile.readText()
        println("spec size: ${yaml.length} bytes")
        val result = parse(yaml)
        println("top keys: ${result.keys}")
        val paths = result["paths"] as? Map<*, *>
        println("paths count: ${paths?.size}")
        assertTrue(paths != null, "paths should exist")
    }
}
