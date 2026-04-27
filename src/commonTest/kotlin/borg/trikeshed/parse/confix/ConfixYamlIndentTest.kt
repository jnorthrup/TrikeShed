package borg.trikeshed.parse.confix

import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.fail

class ConfixYamlIndentTest {
    @Test
    fun nestedMapKeysStayNested() {
        val yaml = """
            |openapi: 1.0
            |info:
            |  title: Test API
            |  version: "1.0"
            |servers:
            |- url: https://example.com
            |""".trimMargin()

        val ctx = contextOf(Syntax.YAML, yaml.asSeries())
        val vTitleTop = Path.resolve(ctx, path("title"))
        if (vTitleTop != null) fail("title should NOT be at top level")

        val vInfo = Path.resolve(ctx, path("info"))
        assertNotNull(vInfo, "info should be at top level")
    }

    @Test
    fun deepNestingWithComponents() {
        // matches Kraken spec structure: 3-level nesting under components
        val yaml = """
            |openapi: 1.0
            |components:
            |  securitySchemes:
            |    apiKeyAuth:
            |      type: apiKey
            |      in: header
            |paths:
            |  /test:
            |    get:
            |      operationId: testOp
            |""".trimMargin()

        val ctx = contextOf(Syntax.YAML, yaml.asSeries())

        // securitySchemes should NOT be at top level
        val vTop = Path.resolve(ctx, path("securitySchemes"))
        if (vTop != null) fail("securitySchemes should NOT be at top level (3-level nesting leak)")

        // type should NOT be at top level
        val vType = Path.resolve(ctx, path("type"))
        if (vType != null) fail("type should NOT be at top level")

        // paths should be at top level
        val vPaths = Path.resolve(ctx, path("paths"))
        assertNotNull(vPaths, "paths should be at top level")
    }

    @Test
    fun sequenceInMap() {
        // servers is a sequence in a map
        val yaml = """
            |openapi: 1.0
            |servers:
            |- url: https://a.com
            |- url: https://b.com
            |paths:
            |  /test:
            |    get:
            |      operationId: testOp
            |""".trimMargin()

        val ctx = contextOf(Syntax.YAML, yaml.asSeries())

        val vPaths = Path.resolve(ctx, path("paths"))
        assertNotNull(vPaths, "paths should be at top level")
    }
}
