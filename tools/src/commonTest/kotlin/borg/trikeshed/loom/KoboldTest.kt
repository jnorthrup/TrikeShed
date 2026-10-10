package borg.trikeshed.loom

import borg.trikeshed.job.*
import borg.trikeshed.util.*
import kotlin.test.*

/**
 * loomctl `kobold` against the cocaine-rats e4898291 `loom-kobold-stage` binary run on the fixture of
 * crates/loomctl/tests/kobold_staging.rs: the stage files it wrote and the messages it printed.
 */
class KoboldTest {
    val ids = mapOf(Kobold.Target.Default48 to "vpzib0ya7g8wse", Kobold.Target.Batch96 to "i0kgmzpysttqfg")

    fun failure(block: () -> Unit): String? = assertFailsWith<IllegalStateException>(block = block).message

    @Suppress("UNCHECKED_CAST")
    fun MutableMap<String, Any>.at(key: String): MutableMap<String, Any> = getValue(key) as MutableMap<String, Any>

    /** kobold_staging.rs `fixture`. */
    fun fixture(target: Kobold.Target): MutableMap<String, Any> {
        @Suppress("UNCHECKED_CAST")
        val value = target.template() as MutableMap<String, Any>
        value["id"] = ids.getValue(target)
        value["args"] = ""
        value.at("workers")["max"] = 0L
        value.at("gpu")["allowedCudaVersions"] = emptyList<Any>()
        value.at("env")["UNRELATED_SECRET"] = "sensitive fixture ' \$(touch never) \n secret"
        value["requestUrls"] = mapOf("base" to "https://${ids.getValue(target)}.api.runpod.ai")
        return value
    }

    fun bytes(value: Any): ByteArray = jsonText(value).encodeToByteArray()

    /** loom-kobold-stage `json_bytes`: pretty JSON and a newline, as the stage files hold it. */
    fun stageFile(value: Any): String = sha256((jsonPretty(value) + "\n").encodeToByteArray()).toLowerHex()

    @Test
    fun stageFilesMatchTheRustStage() {
        val expected = mapOf(
            Kobold.Target.Default48 to listOf(
                "b967dc48edddf36d0fb9c73c9e709d94e45c36773f9649f4602752855279477c",
                "e6c068d5b810e22ef5cd6a5f1be4570bf3e099e66e05af4d4b2c413133bed76e",
                "80478f7e0af49a693d1180c40f08192b41d1188062bf57c4bf4f540a4f1ce912",
                "8303d75045a48bd78b0a0bac7cc2e6a83d9041e22dfc35ee2aba07fa6d7ae0da",
            ),
            Kobold.Target.Batch96 to listOf(
                "c0f2e6102ddb1d13fc0100ee883ac0c79d16a70457513c87ad8230f296216f37",
                "084cab447b4fdc2f98660810f31767007280cac1cbaa12a9020302aae111a3d2",
                "3bd048f277d4444283f8a05ff390c6efd0c3a5a9059c2b88a852e483507e04bc",
                "334d00052767875da55145e00b89dc82836df0aac2d524b7355e00cd1e3e949e",
            ),
        )
        for ((target, files) in expected) {
            val source = bytes(fixture(target))
            val stage = Kobold.prepare(target, ids.getValue(target), source)
            assertEquals(files, listOf(Kobold.digest(source), stageFile(stage.patch), stageFile(stage.rollback), stageFile(stage.manifest())))
        }
    }

    @Test
    fun verificationMatchesTheRustStage() {
        for (target in Kobold.Target.entries) {
            val original = fixture(target)
            val stage = Kobold.prepare(target, ids.getValue(target), bytes(original))
            val applied = LinkedHashMap(original).apply { putAll(stage.patch) }
            stage.verifyPrecondition(bytes(original))
            stage.verifyReadback(bytes(applied))
            stage.verifyRollback(bytes(original))
            @Suppress("UNCHECKED_CAST")
            val command = json((stage.patch.getValue("args") as String).encodeToByteArray(), unique = true) as Map<String, Any>
            stage.verifyReadback(bytes(LinkedHashMap(applied).apply { putAll(command) }))
            assertEquals("complete endpoint snapshot changed; regenerate stage", failure { stage.verifyPrecondition(bytes(LinkedHashMap(original).apply { put("flashboot", "OFF") })) })
            assertEquals("complete endpoint snapshot changed; regenerate stage", failure { stage.verifyPrecondition(bytes(applied)) })
            assertEquals("unrelated provider configuration changed", failure { stage.verifyReadback(bytes(LinkedHashMap(applied).apply { put("timeout", 1L) })) })
            assertEquals("contradictory provider command representations", failure { stage.verifyReadback(bytes(LinkedHashMap(applied).apply { put("entrypoint", listOf("/bin/sh")) })) })
            assertEquals("provider command or complete environment readback differs", failure { stage.verifyReadback(bytes(original)) })
            assertEquals("provider command or complete environment readback differs", failure { stage.verifyRollback(bytes(applied)) })
        }
    }

    @Test
    fun refusalsMatchTheRustStage() {
        val target = Kobold.Target.Default48
        fun variant(change: MutableMap<String, Any>.() -> Unit): ByteArray = bytes(fixture(target).apply(change))
        val refusals = mapOf(
            variant { put("id", "otherid") } to "unexpected endpoint identity",
            variant { at("workers")["max"] = 1L } to "endpoint must already have zero worker capacity",
            variant {}.decodeToString().replace("\"min\":0", "\"min\":-0").encodeToByteArray() to
                "endpoint must already have zero worker capacity",
            variant { at("gpu")["allowedCudaVersions"] = listOf("12.8") } to "unexpected CUDA version restriction",
            variant { put("networkVolumes", listOf("a", "b")) } to "Xet stage requires one network volume",
            variant { put("networkVolumes", listOf("UPPER")) } to "Xet stage requires one network volume",
            variant { at("env")["X"] = 1L } to "environment must contain only strings",
            variant { at("env")["PORT"] = "5002" } to "endpoint health configuration differs from reviewed profile",
            variant { put("args", """{"foo":[]}""") } to "unknown original command field",
            variant { put("args", """{"entrypoint":"x"}""") } to "command must contain argument arrays",
            variant { put("args", """{"cmd":["a"]}"""); put("cmd", listOf("b")) } to "contradictory provider command representations",
            variant { put("args", "[1]") } to "protected JSON must be an object",
            variant { put("args", "not json") } to "invalid or ambiguous protected JSON",
            variant { remove("args") } to "exact original args string is required",
            variant { put("disk", 81L) } to "endpoint does not match reviewed profile",
            variant { remove("gpu") } to "endpoint GPU selection differs from reviewed profile",
            variant { put("env", emptyList<Any>()) } to "environment must be a complete string map",
            bytes(fixture(target)).let { it.copyOf(it.size - 1) + ""","id":"vpzib0ya7g8wse"}""".encodeToByteArray() } to "invalid or ambiguous protected JSON",
            "[1,2]".encodeToByteArray() to "protected JSON must be an object",
            ByteArray(0) to "invalid or ambiguous protected JSON",
        )
        for ((input, message) in refusals) assertEquals(message, failure { Kobold.prepare(target, ids.getValue(target), input) })
        Kobold.prepare(target, ids.getValue(target), variant { put("args", """{"cmd":["a"]}"""); put("cmd", listOf("a")); put("entrypoint", emptyList<Any>()) })
        assertEquals("unknown launch profile", failure { Kobold.Target.parse("24gb") })
        assertEquals(
            "usage: loom-kobold-stage prepare <48gb-mtp|96gb-batch> <exact-endpoint-id> <private-v2-get.json> <new-stage-dir>; or <precondition|readback|rollback-readback> <stage-dir> <private-v2-get.json>",
            failure { loomKoboldStage(arrayOf()) },
        )
    }
}
