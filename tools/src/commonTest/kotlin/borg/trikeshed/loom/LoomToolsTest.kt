package borg.trikeshed.loom

import kotlin.test.*

/** Outputs of the cocaine-rats e4898291 binaries `loom-registry` and `loom-mesh --confix-check` on the same inputs. */
class LoomToolsTest {
    val oci = "src/commonTest/resources/oci"

    fun failure(block: () -> Unit): String? = assertFailsWith<IllegalStateException>(block = block).message

    @Test
    fun verifyLayoutMatchesLoomRegistry() {
        assertEquals(
            listOf("sha256:429a66d25673507f90e93292c1ebf44088600041b082b413861b4c4d87465e06 latest"),
            verifyLayout("$oci/good").map { (digest, name) -> "$digest $name" },
        )
        // The queue is a stack: the last layer is checked first, so the mismatch shadows the missing blob.
        assertEquals("layout blob mismatch", failure { verifyLayout("$oci/two-broken") })
        assertEquals("layout blob missing", failure { verifyLayout("$oci/missing-blob") })
        assertEquals("layout json", failure { verifyLayout("$oci/index-not-json") })
        assertEquals("layout read", failure { verifyLayout("$oci/absent") })
        assertEquals("usage: loom-registry verify <oci-layout-dir> | serve <config.json>", failure { loomRegistry(arrayOf("verify")) })
    }

    @Test
    fun confixReportMatchesLoomMesh() {
        val reports = mapOf(
            """ {"":{},"escaped\"key":[null,true,false,"","\ud83d\udc26","line\nnext",-1.25e+3,9007199254740993,18446744073709551615],"empty":[]} """ to
                """{"bytes":131,"tokens":16,"sha256":"231d2b6749b953004cedef5fea06c0c36d2684bd630a354c1338675e9cb6d4bc"}""",
            """{"k":18446744073709551615,"j":18446744073709551616}""" to
                """{"bytes":51,"tokens":5,"sha256":"a62c317bd2107b8c0b9000339c52b9ce74ab872e563eba76ba8207dacbb673d0"}""",
            "[".repeat(16) + "0" + "]".repeat(16) to
                """{"bytes":33,"tokens":17,"sha256":"f955de38ce972b6a49fa8fae45dd8e2eede435f0d684f7eddec46135e473d6c2"}""",
            """{"k":1e-400}""" to """{"bytes":12,"tokens":3,"sha256":"326ed413103a3f846a70179be62aaf6a4f1f62e4061b3cd927fd40f6da5825d9"}""",
            "5" to """{"bytes":1,"tokens":1,"sha256":"ef2d127de37b942baad06145e54b0c619a1f22327b2ebbcfbec78f5564afe39d"}""",
        )
        for ((input, report) in reports) assertEquals(report, assertConfix(input.encodeToByteArray()).toString())
        val failures = mapOf(
            "" to "configuration size limit",
            """{"x":1,"x":2}""" to "invalid JSON",
            """{"a":1,"\u0061":2}""" to "invalid JSON",
            """{"x":1}=""" to "invalid JSON",
            """{"k":1e400}""" to "invalid JSON",
            "[".repeat(128) + "]".repeat(128) to "invalid JSON",
            "[".repeat(17) + "0" + "]".repeat(17) to "configuration complexity limit",
            "[" + List(2048) { "0" }.joinToString(",") + "]" to "configuration complexity limit",
        )
        for ((input, message) in failures) assertEquals(message, failure { assertConfix(input.encodeToByteArray()) })
        assertEquals("configuration paths required", failure { loomMesh(arrayOf("--confix-check")) })
    }
}
