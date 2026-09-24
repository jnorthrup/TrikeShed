package borg.trikeshed.landscape

import borg.trikeshed.parse.json.JsonSupport

/**
 * LandscapeActivity — the evidence taxonomy of the spatial blackboard medium,
 * ported clean-room from the excised `landscape.js` (feb5af4db).
 *
 * Every lamp on the landscape is derived, not asserted: a program's state is
 * the strongest version-matched run receipt (with freshness, draft, and
 * inspection-only gates), a node's state is the program evidence refined by
 * that node's own recorded outputs, a bare fact is a recorded event at best.
 * Pure commonMain over the parsed fact plane; the adapter supplies browser
 * evidence (timer armed, subscription open) as explicit parameters.
 */

enum class ActivityState(val label: String, val color: String, val hint: String) {
    OPERATIONAL("Operational", "#69cd92", "Observed running program or armed browser source; not proof every child is executing"),
    WAITING("Waiting", "#e4c66f", "Validation or an explicit waiting state"),
    COMPLETED("Completed", "#7baedc", "A recorded result, not a currently running process"),
    STALE("Stale", "#d9a066", "Completed against inputs that have since changed; rebuild to refresh"),
    BLOCKED("Blocked", "#ee8884", "Recorded failure, refusal or timeout"),
    INERT("Inert", "#a9a0ba", "Explicitly stopped, cancelled or inspection-only"),
    UNKNOWN("Unknown", "#9da6ac", "Missing, disconnected, expired or different-version evidence"),
}

data class ProgramEvidence(
    val state: ActivityState,
    val reason: String,
    val receipt: Map<String, Any?>? = null,
    val atMs: Long? = null,
    val activeRuns: Int = 0,
    val lastReceipt: Map<String, Any?>? = null,
)

data class RunReceipt(val key: String, val fields: Map<String, Any?>, val stale: Map<String, Any?>?) {
    fun text(name: String): String? = fields[name] as? String
    fun num(name: String): Double? = (fields[name] as? Number)?.toDouble()
}

data class SharedReference(val from: String, val to: String, val kind: String)

fun Map<*, *>.field(name: String): Any? = entries.firstOrNull { it.key == name }?.value

fun Map<*, *>.hasField(name: String): Boolean = entries.any { it.key == name }

data object LandscapeActivity {

    private val liveStatuses = listOf("running", "validating")

    private val statusStates: Map<String, ActivityState> = mapOf(
        "running" to ActivityState.OPERATIONAL,
        "validating" to ActivityState.WAITING,
        "completed" to ActivityState.COMPLETED,
        "failed" to ActivityState.BLOCKED,
        "refused" to ActivityState.BLOCKED,
        "timed_out" to ActivityState.BLOCKED,
        "cancelled" to ActivityState.INERT,
        "interrupted" to ActivityState.INERT,
    )

    /** group `lcnc/run/` receipts by program, newest first, each carrying its stale marker when one exists */
    fun latest(board: Map<String, Any?>): Map<String, List<RunReceipt>> {
        val runs = HashMap<String, MutableList<RunReceipt>>()
        for ((key, r) in board) {
            if (!key.startsWith("lcnc/run/") || r !is Map<*, *>) continue
            val fields = r.entries.associate { (a, b) -> a.toString() to b }
            val programKey = fields["programKey"] as? String ?: continue
            val stale = (board["lcnc/stale/" + fields["runId"]] as? Map<*, *>)
                ?.entries?.associate { (a, b) -> a.toString() to b }
            runs.getOrPut(programKey) { mutableListOf() }.add(RunReceipt(key, fields, stale))
        }
        for (list in runs.values) list.sortWith(
            compareByDescending<RunReceipt> { it.num("startedAtMs") ?: 0.0 }
                .thenByDescending { it.num("sequence") ?: 0.0 }
        )
        return runs
    }

    /** the strongest evidence for one program version: version-matched receipts, freshness-gated */
    fun program(
        name: String,
        entry: Map<String, Any?>?,
        runs: Map<String, List<RunReceipt>>,
        draft: Boolean,
        connected: Boolean,
        now: Double,
    ): ProgramEvidence {
        val none = ProgramEvidence(ActivityState.UNKNOWN, "No run receipt for this program version")
        if (draft) return none.copy(reason = "Unpublished draft; published receipts do not describe these edits")
        val inspectionOnly =
            ((entry?.get("document") as? Map<*, *>)?.field("controls") as? Map<*, *>)?.field("inspectionOnly") == true
        if (inspectionOnly) return ProgramEvidence(ActivityState.INERT, "Inspection-only assembly; execution disabled")

        val history = runs["lcnc/program/" + name].orEmpty()
        val programCid = entry?.get("programCid") as? String
        val matching = history.filter { programCid != null && it.text("programCid") == programCid }
        val active = matching.filter { it.text("status") in liveStatuses }
        val receipt = active.firstOrNull() ?: matching.firstOrNull()
            ?: return if (history.isNotEmpty()) none.copy(
                lastReceipt = history[0].fields,
                reason = "No version-matched receipt; last recorded run was " + history[0].text("status"),
            ) else none

        val base = ProgramEvidence(
            ActivityState.UNKNOWN, "",
            receipt = receipt.fields,
            atMs = (receipt.num("finishedAtMs") ?: receipt.num("startedAtMs"))?.toLong(),
            activeRuns = active.size,
        )
        if (active.isNotEmpty()) {
            if (!connected) return base.copy(state = ActivityState.UNKNOWN, reason = "Disconnected; last observed run was " + receipt.text("status"))
            val timeout = (receipt.fields["budgets"] as? Map<*, *>)?.field("timeoutMs") as? Number
            val startedAtMs = receipt.num("startedAtMs")
            if (startedAtMs == null || timeout == null || now > startedAtMs + timeout.toDouble() + 5000)
                return base.copy(state = ActivityState.UNKNOWN, reason = "Run freshness unconfirmed; no timely terminal receipt")
        }
        // Stale (Forge genesis, Cut S): completed, but the run's consumed inputs moved since; the marker names them.
        if (receipt.text("status") == "completed" && receipt.stale != null && active.isEmpty())
            return base.copy(state = ActivityState.STALE, reason = "Completed against inputs that have since changed: " + staleInputNames(receipt.stale))
        val status = receipt.text("status")
        return base.copy(
            state = statusStates[status] ?: ActivityState.UNKNOWN,
            reason = "Program $status" + if (active.size > 1) "; " + active.size + " concurrent runs" else "",
        )
    }

    /** refine program evidence for one node; browser liveness arrives as explicit adapter evidence */
    fun node(
        id: String,
        program: ProgramEvidence,
        timerArmed: Boolean = false,
        eventSubscriptionOpen: Boolean = false,
    ): ProgramEvidence {
        if (timerArmed) return ProgramEvidence(ActivityState.OPERATIONAL, "Browser timer armed; this is not proof of a server invocation")
        if (eventSubscriptionOpen) return ProgramEvidence(ActivityState.OPERATIONAL, "Browser event subscription connected")
        if (program.state == ActivityState.INERT && program.receipt == null) return program
        val r = program.receipt
        if (program.state == ActivityState.STALE) {
            // Only the node whose recorded output carries a moved cid turns Stale; the rest stay Completed.
            val moved = (((r?.get("stale") as? Map<*, *>)?.field("inputs") as? List<*>) ?: emptyList<Any?>())
                .mapNotNull { ((it as? Map<*, *>)?.field("oldCid")) as? String }.toSet()
            val out = (r?.get("outputs") as? Map<*, *>)?.field(id)
            if (out != null && moved.isNotEmpty() && JsonSupport.stringify(out).split('"').any(moved::contains))
                return program.copy(state = ActivityState.STALE, reason = "Node $id read an input that has since changed")
            if (r?.get("status") == "completed" && (r["outputs"] as? Map<*, *>)?.hasField(id) == true)
                return program.copy(state = ActivityState.COMPLETED, reason = "Output recorded for node $id; its inputs did not move")
        }
        if (r?.get("status") == "completed" && (r["outputs"] as? Map<*, *>)?.hasField(id) == true)
            return program.copy(state = ActivityState.COMPLETED, reason = "Output recorded for node $id")
        val namedByViolation = r?.get("phase") == "validation" &&
            ((r["violations"] as? List<*>) ?: emptyList<Any?>()).any {
                (it as? Map<*, *>)?.field("toNode") == id || (it as? Map<*, *>)?.field("fromNode") == id
            }
        if (namedByViolation) return program.copy(state = ActivityState.BLOCKED, reason = "Validation violation names node $id")
        return program.copy(
            state = ActivityState.UNKNOWN,
            reason = if (r != null) program.reason + "; node execution phase not reported" else program.reason,
        )
    }

    fun fact(key: String, value: Any?): ProgramEvidence {
        val event = (value as? Map<*, *>)?.field("event") as? String
        if (key.startsWith("kanban/committed/") || key.startsWith("kanban/review/") || key.startsWith("kanban/rule/") ||
            (key.startsWith("narsese/") && event in listOf("minted", "revised", "dependent-rete-firing"))
        ) return ProgramEvidence(
            ActivityState.COMPLETED, "Recorded event, not current worker activity",
            atMs = (value as? Map<*, *>)?.field("atMs")?.let { (it as? Number)?.toLong() },
        )
        return ProgramEvidence(ActivityState.UNKNOWN, "No execution lifecycle on this fact")
    }

    /** shared atoms and shared jobs: cross-references between facts that name the same id, capped and deduped */
    fun references(board: Map<String, Any?>): List<SharedReference> {
        val atoms = HashMap<String, MutableList<String>>()
        val jobs = HashMap<String, MutableList<String>>()
        val links = mutableListOf<SharedReference>()
        val seen = HashSet<String>()

        fun index(map: MutableMap<String, MutableList<String>>, id: Any?, key: String) {
            if (id !is String && id !is Number) return
            val k = id.toString()
            if (k.isEmpty()) return
            val list = map.getOrPut(k) { mutableListOf() }
            if (list.size < 16) list.add(key)
        }

        for ((key, v) in board) {
            if (v !is Map<*, *>) continue
            val fields = v.entries.associate { (a, b) -> a.toString() to b }
            index(atoms, fields["angular"] ?: if (key.startsWith("kanban/review/")) key.substring(14) else null, key)
            var job = fields["jobId"]
            if (job == null && key.startsWith("kanban/committed/")) {
                val tail = key.substring(17)
                val slash = tail.lastIndexOf('/')
                // the source's slice(0, lastIndexOf) drops the last character when no slash is present; preserved
                job = if (slash >= 0) tail.substring(0, slash) else tail.dropLast(1)
            }
            index(jobs, job, key)
        }

        for ((groups, kind) in listOf(atoms to "shared atom reference", jobs to "shared job reference")) {
            for (keys in groups.values) {
                var i = 1
                while (i < keys.size && links.size < 128) {
                    val from = keys[0]
                    val to = keys[i]
                    if (seen.add(from + "\n" + to + "\n" + kind)) links += SharedReference(from, to, kind)
                    i++
                }
            }
        }
        return links
    }

    private fun staleInputNames(stale: Map<String, Any?>): String {
        val inputs = ((stale["inputs"] as? List<*>) ?: emptyList<Any?>()).mapNotNull { input ->
            (input as? Map<*, *>)?.let { (it.field("id") as? String) ?: ((it.field("project") as? String)?.let { p -> "$p/ listing" }) }
        }
        return inputs.joinToString(", ").ifEmpty { stale["count"]?.toString() ?: "" }
    }
}
