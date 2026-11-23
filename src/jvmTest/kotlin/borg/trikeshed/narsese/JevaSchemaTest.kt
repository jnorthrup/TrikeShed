package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.parse.reifyMap
import borg.trikeshed.reactor.xet.XetHashing
import borg.trikeshed.userspace.nio.channels.spi.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.*

/** The host's model schema as JevaConfig takes it. mlnix and the 4-bit checkpoint are explicit opt-ins. */
class JevaSchemaTest {
    @org.junit.jupiter.api.io.TempDir lateinit var directory: Path

    /** One mlnix evaluation, answered here. */
    private class Mlnix(val reply: () -> ProcessResult) : ProcessOperations {
        var asked = 0
        var command = ""
        var environment: Map<String, String> = emptyMap()
        override suspend fun exec(command: String, args: List<String>, stdin: ByteArray?, env: Map<String, String>): ProcessResult {
            asked++
            this.command = (_l[command] + args).joinToString(" ")
            environment = env
            return reply()
        }
    }

    private fun answered(json: String) = ProcessResult(0, json.encodeToByteArray(), ByteArray(0))
    private val settings = """{"TRIKESHED_JEVA_MODEL":"/held/q4","TRIKESHED_JEVA_DEVICE":"mlx",""" +
        """"TRIKESHED_JEVA_RUNTIME":"kotlin","TRIKESHED_JEVA_WORKER":"/linked/jevaWorker.kexe"}"""

    @Test fun schemaStandsWhereNoModelIsConfigured(): Unit = runBlocking {
        val mlnix = Mlnix { answered(settings) }
        val environment = _m["HOME" j "/home/operator", "TRIKESHED_JEVA_TIMEOUT_MS" j "5000", "JEV_API_KEY" j "secret"]
        val config = JevaConfig.host({ null }, environment::get, mlnix)
        assertEquals(JevaConfig(model = "/held/q4", device = "mlx", worker = "/linked/jevaWorker.kexe", timeoutMs = 5_000), config)
        assertEquals("${Jeva.MLNIX} eval --json ${Jeva.SCHEMA} -A jeva", mlnix.command)
        assertEquals(_m["HOME" j "/home/operator"], mlnix.environment)
    }

    @Test fun configuredSettingsStandBeforeTheSchema(): Unit = runBlocking {
        val unasked = Mlnix { error("a configured model asks no schema") }
        assertEquals("/operator/model", JevaConfig.host({ null }, _m["TRIKESHED_JEVA_MODEL" j "/operator/model"]::get, unasked).model)
        assertEquals(0, unasked.asked)
        val config = JevaConfig.host(_m["trikeshed.jeva.worker" j "/operator/worker"]::get, { null }, Mlnix { answered(settings) })
        assertEquals("/operator/worker", config.worker)
        assertEquals("/held/q4", config.model)
    }

    @Test fun refusalIsTheLaneUnavailability(): Unit = runBlocking {
        val refusals: List<Join<() -> ProcessResult, String>> = _l[
            { answered("""{"refusal":"no cache holds q4"}""") } j "no cache holds q4",
            { ProcessResult(1, ByteArray(0), "error: undefined variable 'x'\n       at model.nix:1:1".encodeToByteArray()) } j "error: undefined variable 'x'",
            { throw java.io.IOException("Cannot run program \"mlnix\"") } j "Cannot run program \"mlnix\"",
            { answered("""{"TRIKESHED_JEVA_MODEL":7}""") } j "Invalid Jeva TRIKESHED_JEVA_MODEL",
        ]
        val unstarted = object : ProcessOperations {
            override suspend fun exec(command: String, args: List<String>, stdin: ByteArray?, env: Map<String, String>): ProcessResult =
                error("a refused lane starts no process")
        }
        for ((reply, reason) in refusals) {
            val config = JevaConfig.host({ null }, { null }, Mlnix(reply))
            assertEquals("Jeva model schema ${Jeva.SCHEMA}: $reason", config.refusal)
            val lane = JevaElement(CoroutineScope(coroutineContext + unstarted), config)
            try {
                val status = lane.availability()
                assertEquals(false, status["available"])
                assertEquals(config.refusal, status["error"])
                assertEquals(config.refusal, assertFails { lane.ask("state", _m["q" j _m["type" j "noul", "instructions" j "Decide"]]) }.message)
            } finally { lane.drain() }
        }
    }

    /** utils/mlnix/model.nix itself, evaluated by mlnix over a checkout made here: what holds and links decides. */
    @Test fun definitionAnswersWhatACheckoutHoldsAndLinks(): Unit = runBlocking {
        val mlnix = java.lang.System.getProperty("trikeshed.native.mlnix")
        assumeTrue(!mlnix.isNullOrBlank(), "mlnix not supplied")
        assumeTrue(java.lang.System.getProperty("os.name") == "Mac OS X" && java.lang.System.getProperty("os.arch") == "aarch64", "the mlx schema is Apple silicon's")
        val definition = directory.resolve(Jeva.SCHEMA)
        Files.createDirectories(definition.parent)
        Files.copy(Path.of(Jeva.SCHEMA), definition)
        val property = _m["trikeshed.jeva.mlnix" j mlnix, "trikeshed.jeva.schema" j definition.toString()]
        suspend fun host() = JevaConfig.host(property::get, { null }, JvmProcessOperations())
        assertContains(host().refusal.orEmpty(), "no cache holds jeva-4b-mlx-q4g32-dist")
        val held = directory.resolve("utils/subvm/jeva-4b-mlx-q4g32-dist")
        for (name in s_["config.yaml", "head.pt", "backbone_mlx/config.json", "backbone_mlx/model.safetensors", "backbone_mlx/tokenizer.json"].view) {
            Files.createDirectories(held.resolve(name).parent)
            Files.writeString(held.resolve(name), name)
        }
        assertContains(host().refusal.orEmpty(), "linkJevaWorkerReleaseExecutableMacos")
        val worker = directory.resolve("build/bin/macos/jevaWorkerDebugExecutable/jevaWorker.kexe")
        Files.createDirectories(worker.parent)
        Files.writeString(worker, "")
        assertEquals(JevaConfig(model = held.toString(), device = "mlx", worker = worker.toString()), host())
        // A worker older than the installed library was linked against another one.
        Files.setLastModifiedTime(worker, FileTime.fromMillis(0))
        assertContains(host().refusal.orEmpty(), "linked since")
    }

    /** The default lane of this host: the schema's 4-bit checkpoint through its worker, against the published reference run. */
    @Test fun hostSchemaAnswersTheReferenceCase(): Unit = runBlocking {
        val mlnix = java.lang.System.getProperty("trikeshed.native.mlnix")
        assumeTrue(System.getenv("TRIKESHED_TEST_JEVA_MLX") == "1" && !mlnix.isNullOrBlank(), "4-bit checkpoint run not requested")
        val processes = JvmProcessOperations()
        val config = JevaConfig.host(_m["trikeshed.jeva.mlnix" j mlnix]::get, System::getenv, processes)
        assertNull(config.refusal)
        // xet: reactor/xet computes the schema's address from the held bytes.
        val xet = reifyMap(processes.exec(requireNotNull(mlnix), _l["eval", "--json", Jeva.SCHEMA, "-A", "xet"]).stdout.decodeToString())
        val head = assertIs<List<*>>(xet["files"]).map { assertIs<Map<*, *>>(it) }.single { it["path"] == "head.pt" }
        assertEquals(head["hash"], XetHashing.toApiHex(XetHashing.fileHash(Files.readAllBytes(Path.of(config.model, "head.pt")))))
        val lane = JevaElement(CoroutineScope(coroutineContext + processes), config.copy(mlxMaxTokens = 2048, mlxMaxFootprintBytes = 6L shl 30))
        try {
            val reply = lane.ask("The production login page returns HTTP 503. All customers are unable to sign in or complete purchases.", _m[
                "outage" j _m["type" j "noul", "instructions" j "Does this report an ongoing service outage?"],
                "team" j _m["type" j "choice", "instructions" j "Which team should handle this support ticket?", "criteria" j _m[
                    "billing" j "Payment disputes, invoices, and refunds.",
                    "engineering" j "Service outages, software errors, and broken features.",
                    "general" j "All other customer support requests."]]])
            val answers = assertIs<Map<*, *>>(reply["answers"])
            // quantization/runtime-smoke.json of jnorthrup/Jeva is the Python MLX lane: 0.9184, and engineering at 0.5927.
            assertEquals(0.9184, assertIs<Double>(assertIs<Map<*, *>>(answers["outage"])["noul"]), 0.02)
            val team = assertIs<Map<*, *>>(answers["team"])
            assertEquals("engineering", team["choice"])
            assertEquals(0.5927, assertIs<Double>(team["confidence"]), 0.02)
        } finally { lane.drain() }
    }
}
