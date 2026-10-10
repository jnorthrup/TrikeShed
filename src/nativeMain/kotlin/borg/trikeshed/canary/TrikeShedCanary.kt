package borg.trikeshed.canary

import borg.trikeshed.parse.jsonOf

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
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.isam.ISAM_LEAF_BYTES
import borg.trikeshed.isam.IsamDataFile
import borg.trikeshed.isam.RecordMeta
import borg.trikeshed.isam.UringIsamOperations
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.tilting.zran.zstdCompress
import borg.trikeshed.tilting.zran.zstdDecompress
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
    check("zstd") {
        val text = "Zstandard frames from the reference encoder must decode in TrikeShed on every target: JVM, macOS arm64, Linux, wasm. The ledger leaves are sixteen KiB of whole rows, columnar by meta-v2 group, each leaf one frame in a seekable table; the fence keeps per-leaf min and max so a key lookup reads one compressed leaf. Zstandard frames from the reference encoder must decode in TrikeShed on every target."
        // `zstd -19` of text, from the reference CLI
        val reference = ("28b52ffd0468050700c6902f1c508d739bd94d4e0b6df3db3e4768810309c241e31f21cbd45155d5432500280028002ebdee300e" +
            "3ddab07bfce5b752b37ea572f795bb9eb9face734b65bc6fa88c97e272f8f61157f47ec233eb9b75e63b0d2f6d31624522b927614530688a6" +
            "62c14362b0ac7c184f746f179a29fee2ecde0991d85b71249a98d031300df9b27102bba26b154563692eab3742266d2860761f73fc9b30e8b9" +
            "7f5dd6bd452edc2ab2309ca649e1c323ad7126c5eae01f49137fa21a6f07c0641e85fe3030b00d1b383e368882e884b1577ebe1d4da5d8db05" +
            "410c86e5d90a6d1c03af7684a572c").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        require(zstdDecompress(reference).decodeToString() == text) { "reference frame decoded wrong" }
        val ours = zstdCompress(text.encodeToByteArray())
        require(zstdDecompress(ours).decodeToString() == text) { "own frame round trip lost bytes" }
        linkedMapOf("reference" to reference.size, "ours" to ours.size)
    }
    check("ledger") {
        val path = "/tmp/trikeshed-canary-ledger.bin"
        val meta = arrayOf(
            RecordMeta("seq", IOMemento.IoLong, 0, 8).also { it.groupId = 0; it.groupName = "seq" },
            RecordMeta("amount", IOMemento.IoLong, 8, 16).also { it.groupId = 1; it.groupName = "amount" },
        )
        val n = 5000
        val rows: Cursor = n j { r: Int -> 2 j { c: Int -> (if (c == 0) r.toLong() else r * 37L % 1000) j meta[c].`↺` } }
        UringIsamOperations(leafBytes = ISAM_LEAF_BYTES).write(rows, path, emptyMap(), false)
        val isam = IsamDataFile(path)
        isam.open()
        try {
            require(isam.size == n) { "ledger holds ${isam.size} rows" }
            for (r in intArrayOf(0, 1, 2047, 2048, 4999)) require(isam[r][0].a == r.toLong() && isam[r][1].a == r * 37L % 1000) { "row $r" }
            val fence = isam.fence("seq")
            linkedMapOf("rows" to n, "leaves" to fence.size, "fence0" to "${fence[0].b.a}..${fence[0].b.b}")
        } finally { isam.close() }
    }

    val receipt = jsonOf(checks)
    val cid = ContentId.of(receipt.encodeToByteArray()).value
    println(receipt)
    println("""{"receiptCid":"$cid","failed":$failed}""")
    if (failed > 0) exitProcess(1)
}
