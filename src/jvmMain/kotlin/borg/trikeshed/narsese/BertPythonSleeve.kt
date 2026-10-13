package borg.trikeshed.narsese

import borg.trikeshed.graal.subvm.*
import borg.trikeshed.hermes.HermesPythonPort
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reify
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.sleeve.*
import borg.trikeshed.vm.Teleported
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path

/** Source-tree Python control under GraalPy; the bound [BertEncoder] owns inference. */
class BertPythonSleeve private constructor(
    private val scope: CoroutineScope,
    private val root: Path,
    private val sleeve: Path,
    private val encoderFactory: suspend (CoroutineScope) -> BertEncoder,
) : BertEncoder {
    companion object {
        const val ENTRY = "bert.encoder"
        private const val RED_TEST = "BertPythonSleeveTest.sourceTreeEncoderReturnsTargets"

        suspend fun local(scope: CoroutineScope): BertPythonSleeve {
            val module = JvmBertEncoder.moduleRoot()
            val root = module.toPath().resolve("src/main/python")
            val sleeve = java.lang.System.getProperty("trikeshed.bert.sleeve")?.let(Path::of)
                ?: requireNotNull(GuestModules.root()) { "BERT sleeve source tree is not installed" }
                    .parentFile.parentFile.toPath().resolve("graalpy-sleeve/bert")
            return open(scope, root, sleeve, JvmBertEncoder::local)
        }

        suspend fun open(scope: CoroutineScope, root: Path, sleeve: Path,
                         encoderFactory: suspend (CoroutineScope) -> BertEncoder): BertPythonSleeve {
            val runtime = BertPythonSleeve(scope, root.toAbsolutePath().normalize(),
                sleeve.toAbsolutePath().normalize(), encoderFactory)
            try {
                withContext(Dispatchers.IO) {
                    runtime.port.importInVm(runtime.port.inventory(runtime.root, runtime.sleeve), ENTRY)
                    runtime.port.evalInVm("from bert.encoder import Encoder\n_bert_encoder = Encoder(None)\nTrue",
                        "bert-entry.py")
                }
                runtime.record(emptyList())
                return runtime
            } catch (failure: Throwable) {
                if (failure !is CancellationException) runtime.red("Encoder", failure)
                try { runtime.drain() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }
    }

    private val gate = Mutex()
    private var closed = false
    private var backend: BertEncoder? = null
    override val model: String get() = backend?.model ?: ENTRY

    private suspend fun encoder(): BertEncoder = backend ?: encoderFactory(scope).also { backend = it }

    private val port = HermesPythonPort(install = { guest ->
        guest.delegate("bert_model") { Teleported.Str(runBlocking { encoder().model }) }
        guest.delegate("bert_encode") { args ->
            val rows = (args.single() as Teleported.Arr).v
            require(rows.size <= 16) { "BERT batch exceeds 16 targets" }
            val tokens = rows.map { value ->
                val fields = (value as Teleported.Obj).v
                BertToken((fields.getValue("lemma") as Teleported.Str).v,
                    (fields.getValue("sentence") as Teleported.Str).v,
                    (fields["start"] as? Teleported.Num)?.v?.toInt(),
                    (fields["end"] as? Teleported.Num)?.v?.toInt())
            }.toSeries()
            val encoded = runBlocking { encoder().encode(tokens) }
            check(encoded.size == tokens.size) { "BERT encoder omitted targets" }
            Teleported.Str(jsonOf(encoded.view.map { vector ->
                mapOf("span" to vector.span.name, "values" to vector.values?.toList())
            }))
        }
    })

    override suspend fun encode(tokens: Series<BertToken>): Series<BertVector> = gate.withLock {
        check(!closed) { "BERT sleeve is closed" }
        require(tokens.size <= 1024) { "BERT request exceeds 1024 targets" }
        for (token in tokens.view) {
            require(token.lemma.isNotBlank() && token.lemma.length <= 128 && token.sentence.length <= 16_384)
            require((token.start == null && token.end == null) || (token.start != null && token.end != null &&
                token.start >= 0 && token.end > token.start && token.end <= token.sentence.length))
        }
        try {
            val result = ArrayList<BertVector>(tokens.size)
            var offset = 0
            while (offset < tokens.size) {
                // A guest crossing owns at most one sentence's inference and sixteen target projections.
                var end = offset + 1
                while (end < minOf(offset + 16, tokens.size) && tokens[end].sentence == tokens[offset].sentence) end++
                val request = jsonOf((offset until end).map { i -> tokens[i].let { token ->
                    buildMap<String, Any> {
                        put("lemma", token.lemma); put("sentence", token.sentence)
                        token.start?.let { put("start", it); put("end", token.end!!) }
                    }
                } })
                val reply = withContext(Dispatchers.IO) {
                    port.evalInVm("import json\njson.dumps(_bert_encoder.encode(json.loads(${pythonString(request)})), allow_nan=False)",
                        "bert-encode.py") as Teleported.Str
                }
                val rows = reify(reply.v) as? List<*> ?: error("BERT sleeve omitted vectors")
                check(rows.size == end - offset) { "BERT sleeve omitted targets" }
                for ((i, value) in rows.withIndex()) {
                    val row = value as Map<*, *>
                    val span = BertSpan.valueOf(row["span"] as String)
                    val values = (row["values"] as? List<*>)?.let { floats ->
                        FloatArray(floats.size) { d -> (floats[d] as Number).toFloat().also { check(it.isFinite()) } }
                    }
                    check((values != null) == (span in setOf(BertSpan.Exact, BertSpan.Stem, BertSpan.Explicit)))
                    result.add(BertVector(tokens[offset + i], span, values))
                }
                offset = end
            }
            if (tokens.size > 0) record(emptyList())
            result.toSeries()
        } catch (failure: Throwable) {
            if (failure !is CancellationException) red("encode", failure)
            throw failure
        }
    }

    override suspend fun drain() = withContext(NonCancellable) {
        gate.withLock {
            if (!closed) {
                closed = true
                try { port.close() } finally {
                    try { backend?.drain() } finally { backend = null }
                }
            }
        }
    }

    private fun record(traps: List<PolyglotTrap>) = AgentSleeveRegistry.record(AgentSleeveManifest(
        id = "bert", facet = VmFacet.GRAAL_PYTHON, entryModule = ENTRY,
        sleeveRoot = sleeve.toString(), donorRoot = root.toString().takeIf { Files.isDirectory(root) }, traps = traps,
    ))

    private fun red(symbol: String, failure: Throwable) {
        val guest = generateSequence(failure) { it.cause }.filterIsInstance<GuestException>().firstOrNull()
        record(listOf(PolyglotTrap(
            id = "bert/$ENTRY", donorModule = ENTRY, symbol = symbol, shape = TrapShape.UNRESOLVED,
            catchability = if (guest?.kind == GuestFailure.DEAD || failure is Error)
                TrapCatchability.HOST_UNCATCHABLE else TrapCatchability.GUEST_CATCHABLE,
            forkPath = "bert/encoder.py", redTest = RED_TEST,
            evidence = "${failure::class.qualifiedName}: ${failure.message}\n${failure.stackTraceToString()}".take(8192),
        )))
    }

    private fun pythonString(value: String): String = "'" + value.replace("\\", "\\\\")
        .replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r") + "'"
}
