package borg.trikeshed.parse.confix

import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.fail

class ConfixYamlKrakenShapeTest {
    @Test
    fun krakenShapeSequenceAtRoot() {
        // matches Kraken spec structure: servers (sequence) + tags (sequence) at root level
        val yaml = """
            |openapi: 1.0
            |servers:
            |- url: https://a.com
            |  description: Server A
            |- url: https://b.com
            |tags:
            |- name: Market Data
            |  description: Public data
            |- name: Trading
            |paths:
            |  /test:
            |    get:
            |      operationId: testOp
            |""".trimMargin()

        val ctx = contextOf(Syntax.YAML, yaml.asSeries())

        val vPaths = Path.resolve(ctx, path("paths"))
        assertNotNull(vPaths, "paths should be at top level")
    }

    @Test
    fun krakenShapeTagsSequence() {
        val yaml = """
            |openapi: 1.0
            |tags:
            |- name: Market Data
            |  description: Public data
            |- name: Trading
            |paths:
            |  /test:
            |    get:
            |      operationId: testOp
            |""".trimMargin()

        val ctx = contextOf(Syntax.YAML, yaml.asSeries())

        val vPaths = Path.resolve(ctx, path("paths"))
        assertNotNull(vPaths, "paths should be at top level after tags sequence")
    }
}
