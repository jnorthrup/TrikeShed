package borg.trikeshed.parse.confix

import borg.trikeshed.lib.toSeries
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.fail

class ConfixYamlMultilineTest {
    @Test
    fun multilinePlainScalar() {
        val yaml = """
            |openapi: 1.0
            |info:
            |  description: Conservative spec built from API pages. This captures documented
            |    endpoint surfaces and response descriptions.
            |  title: Test
            |""".trimMargin()

        val ctx = contextOf(Syntax.YAML, yaml.toSeries())

        // info should have title (not broken by multiline description)
        val vInfo = Path.resolve(ctx, path("info"))
        assertNotNull(vInfo, "info should be at top level")
    }

    @Test
    fun multilineDoesNotLeakKeys() {
        val yaml = """
            |openapi: 1.0
            |info:
            |  description: Line 1
            |    line 2 continuation
            |    line 3 continuation
            |  version: "1.0"
            |paths:
            |  /test:
            |    get:
            |      operationId: testOp
            |""".trimMargin()

        val ctx = contextOf(Syntax.YAML, yaml.toSeries())

        // multiline continuation should NOT break subsequent keys
        val vPaths = Path.resolve(ctx, path("paths"))
        assertNotNull(vPaths, "paths should be at top level after multiline scalar")

        // line 3 should NOT be a key
        val vCont = Path.resolve(ctx, path("line 3 continuation"))
        if (vCont != null) fail("multiline continuation leaked as key")
    }
}
