package borg.trikeshed.canary

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.InMemoryPromptReads
import borg.trikeshed.lcnc.LcncContracts
import borg.trikeshed.lcnc.LcncNode
import borg.trikeshed.lcnc.LcncProgram
import borg.trikeshed.lcnc.LcncRunner
import borg.trikeshed.lcnc.LcncTypeCheck
import borg.trikeshed.lcnc.LcncWire
import borg.trikeshed.lcnc.PromptDocument
import borg.trikeshed.lcnc.PromptNodes
import borg.trikeshed.lcnc.PromptTemplate
import borg.trikeshed.lcnc.PureNodes
import borg.trikeshed.lib.toSeries
import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.runBlocking
import kotlin.native.Platform
import kotlin.system.exitProcess

/**
 * THE CANARY IN THE COAL MINE — a native, POSIX, non-Linux binary (macOS arm64)
 * that runs commonMain end to end with no JVM underneath it.
 *
 * Jim, 2026-09-06: "we desire a macos binary on arm as posix non-linux canary".
 * Every check below is commonMain code the daemon also runs: Confix parse and
 * canonical CBOR identity, a CAS round trip, a stored prompt's identity and
 * render, and an LCNC program walked by the one executor with the prompt legos.
 * A JVM leak into commonMain fails the link; a semantic drift fails a check.
 * The output is a receipt with its own content id, so a run is citable.
 */
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
fun main(args: Array<String>) {
    val checks = linkedMapOf<String, Any?>()
    var failed = 0
    fun check(name: String, block: () -> Any?) {
        try {
            checks[name] = block()
        } catch (t: Throwable) {
            failed++
            checks[name] = "FAIL: " + (t.message ?: t::class.simpleName ?: "error")
        }
    }

    checks["platform"] = Platform.osFamily.name + "/" + Platform.cpuArchitecture.name
    checks["args"] = args.toList()

    check("confix") {
        val doc = confixDoc("""{"kind":"canary","n":[1,2,3],"ok":true}""")
        ContentId.of(doc).value
    }
    check("cas") {
        val bytes = "hello, canary".encodeToByteArray()
        val cas = CasStore.inMemory()
        val cid = cas.put(bytes)
        require(cas.get(cid)?.decodeToString() == "hello, canary") { "CAS round trip lost bytes" }
        require(ContentId.of(bytes) == cid) { "content id mismatch" }
        cid.value
    }
    check("prompt") {
        val doc = PromptDocument("canary", "Greet {{who}} from {{where}}.")
        linkedMapOf(
            "cid" to doc.cid,
            "variables" to doc.variables,
            "rendered" to PromptTemplate.render(doc.text, mapOf("who" to "Jim", "where" to "macOS arm64")),
        )
    }
    check("lcnc") {
        val reads = InMemoryPromptReads().apply { put(PromptDocument("canary", "Greet {{who}}.")) }
        val program = LcncProgram(
            "canary",
            listOf(
                LcncNode("pr", PromptNodes.GET, params = mapOf("name" to "canary")),
                LcncNode("args", "json.value", params = mapOf("value" to """{"who":"the canary"}""")),
                LcncNode("render", PromptNodes.RENDER),
                LcncNode("out", LcncContracts.SCOPE_OUT, params = mapOf("name" to "text")),
            ).toSeries(),
            listOf(
                LcncWire("pr", "text", "render", "template"),
                LcncWire("args", "value", "render", "args?"),
                LcncWire("render", "text", "out", "value"),
            ).toSeries(),
        )
        val violations = LcncTypeCheck.check(program)
        require(violations.isEmpty()) { "type check: $violations" }
        val runner = LcncRunner(PureNodes.registry { 0L } + PromptNodes.registry(reads))
        val result = runBlocking { runner.runProcedure(program) }
        val text = result.returns["text"] ?: error("the ring yielded nothing")
        require(text == "Greet the canary.") { "unexpected yield: $text" }
        linkedMapOf("text" to text, "nodes" to result.nodeOutputs.size)
    }
    checks["contracts"] = LcncContracts.all().size

    val receipt = JsonSupport.stringify(checks)
    val cid = ContentId.of(receipt.encodeToByteArray()).value
    println(receipt)
    println("""{"receiptCid":"$cid","failed":$failed}""")
    if (failed > 0) exitProcess(1)
}
