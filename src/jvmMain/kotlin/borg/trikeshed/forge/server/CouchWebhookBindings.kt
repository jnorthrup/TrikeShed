package borg.trikeshed.forge.server

import borg.trikeshed.couch.CouchStore
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.hook.CausalHookDeliveryLedger
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lcnc.LcncProgramConfix
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Inbound wire + WAL ledger. Outbound `_changes` delivery uses its own lane (separate WAL). */
data class CouchWebhookRuntime(val wire: WebhookWire, val ledger: CausalHookDeliveryLedger)

/**
 * The LCNC dispatch half of the inbound intake: resolve `program` to its panels
 * document, find `node`, and run it with the delivery body as the named port's
 * input. Every delivery lands an auditable `hook-intake/` key first; the run (or
 * the reason there was none) lands as `hook-run/`. The loader is a seam so gates
 * inject fixture programs without a store.
 */
fun lcncHookIntake(
    blackboard: ConfixBlackboard,
    runners: Map<String, LcncNodeRunner>,
    loadProgram: suspend (String) -> ByteArray?,
): HookEnvelopeIntake = HookEnvelopeIntake { e ->
    blackboard.put(
        "hook-intake/${e.program}/${e.node}/${e.port}/${e.nuid}",
        mapOf(
            "program" to e.program,
            "node" to e.node,
            "port" to e.port,
            "deliveryNuid" to e.nuid,
            "body" to e.body,
        ),
        "webhook",
    )
    val runKey = "hook-run/${e.program}/${e.node}/${e.port}/${e.nuid}"
    val outcome: Map<String, Any?> = runCatching {
        val bytes = loadProgram(e.program)
            ?: return@runCatching mapOf("status" to "no-such-program")
        val program = LcncProgramConfix.fromJson(e.program, bytes.decodeToString())
        var target: borg.trikeshed.lcnc.LcncNode? = null
        for (i in 0 until program.nodes.size) {
            if (program.nodes[i].id == e.node) { target = program.nodes[i]; break }
        }
        val node = target ?: return@runCatching mapOf("status" to "no-such-node")
        val runner = runners[node.type]
            ?: return@runCatching mapOf("status" to "no-runner", "type" to node.type)
        val payload = runCatching { JsonSupport.parse(e.body) }.getOrElse { e.body }
        val outputs = runner.run(node, mapOf(e.port to payload))
        mapOf(
            "status" to "ran",
            "type" to node.type,
            "outputs" to JsonSupport.stringify(outputs.mapValues { it.value?.toString() }),
        )
    }.getOrElse { t -> mapOf("status" to "error", "error" to (t.message ?: t.toString())) }
    blackboard.put(runKey, outcome, "webhook")
}

/** Build the production inbound hook runtime from CAS/Couch subscription docs + CausalWal. */
suspend fun couchWebhookRuntime(
    couch: CouchStore,
    blackboard: ConfixBlackboard,
    stateDir: File,
    /** The host-composed LCNC runner map (ModuleContext.lcncRunners) — a LIVE reference; modules attach later. */
    runners: Map<String, LcncNodeRunner> = emptyMap(),
    /** Loads a program document's bytes by name; null = unresolvable. Production binds panels/<name>. */
    loadProgram: suspend (String) -> ByteArray? = { null },
): CouchWebhookRuntime {
    val ledger = withContext(Dispatchers.IO) {
        CausalHookDeliveryLedger.open(File(stateDir, ".hook-deliveries.wal"))
    }
    val wire = WebhookWire(
        hooks = InboundHookLookup { program, node, port ->
            val doc = couch.all().firstOrNull { d ->
                d.id.startsWith("hooks/") &&
                    d.fields.any { it.name == "program" && it.value?.toString() == program } &&
                    d.fields.any { it.name == "node" && it.value?.toString() == node } &&
                    d.fields.any { it.name == "port" && it.value?.toString() == port }
            } ?: return@InboundHookLookup null
            val secret = doc.fields.firstOrNull { it.name == "hmacSecret" }?.value?.toString()
                ?: return@InboundHookLookup null
            InboundHook(secret)
        },
        ledger = ledger,
        intake = lcncHookIntake(blackboard, runners, loadProgram),
    )
    return CouchWebhookRuntime(wire, ledger)
}
