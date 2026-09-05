package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.parse.json.JsonSupport

/**
 * LCNC ON THE BLACKBOARD.
 *
 * The vocabulary, every program, and every cable with its EXACT type are
 * entries on the daemon's one blackboard — not a private store beside it.
 * The surface reads these entries and subscribes to their deltas; it does not
 * have to show a cable's type, it must obey it, and a program whose cables do
 * not obey carries its violations on the same entry. The run seam obeys the
 * entry: its violations refuse the run; its document is what runs.
 *
 *     lcnc/vocabulary          contracts + composites, kinds, acceptance, refinements, bindings
 *     lcnc/program/<name>      { name, document, cables: [{from, to, type}], violations, sourceCid }
 *
 * `sourceCid` is the content id of the SOURCE the entry was seeded from — a
 * preset's or a saved panel's bytes. The board is the authority: an entry
 * edited on the board keeps its `sourceCid` and is obeyed as edited; the
 * source overwrites it only when the source itself changes.
 */
object LcncBlackboard {

    const val VOCABULARY = "lcnc/vocabulary"
    const val PROGRAM_PREFIX = "lcnc/program/"

    fun programKey(name: String): String = PROGRAM_PREFIX + name

    /** The content id of a program's canonical Confix bytes — the same cid a panel save mints. */
    fun cidOf(program: LcncProgram): String =
        ContentId.of(LcncProgramConfix.toJson(program).encodeToByteArray()).value

    /** The entry for one program: its document, its cables each with the exact type it carries, its violations. */
    fun programEntry(
        name: String,
        program: LcncProgram,
        contracts: Map<String, LcncPortContract>,
        sourceCid: String? = cidOf(program),
    ): Map<String, Any?> {
        val types = LcncTypeCheck.cableTypes(program, contracts)
        val cables = (0 until program.wires.size).map { i ->
            val w = program.wires[i]
            linkedMapOf("from" to listOf(w.fromNode, w.fromPort), "to" to listOf(w.toNode, w.toPort), "type" to types[i])
        }
        return linkedMapOf(
            "name" to name,
            "document" to JsonSupport.parse(LcncProgramConfix.toJson(program)),
            "cables" to cables,
            "violations" to LcncTypeCheck.check(program, contracts, strict = false).map { it.toMap() },
            "sourceCid" to sourceCid,
            "programCid" to cidOf(program),
        )
    }

    /** A program read back from its entry; null when the entry is absent or not a program. */
    fun programOf(entry: Any?): LcncProgram? {
        val m = entry as? Map<*, *> ?: return null
        val name = m["name"]?.toString() ?: return null
        val doc = m["document"] ?: return null
        return runCatching { LcncProgramConfix.fromJson(name, JsonSupport.stringify(doc)) }.getOrNull()
    }

    /** The entry's document as Confix JSON text — what `/api/panels/<name>` serves for a board-only program. */
    fun documentJsonOf(entry: Any?): String? =
        (entry as? Map<*, *>)?.get("document")?.let { JsonSupport.stringify(it) }

    fun sourceCidOf(entry: Any?): String? = (entry as? Map<*, *>)?.get("sourceCid")?.toString()

    /** The violations the publisher recorded on the entry; null when the entry carries none (a raw assert). */
    @Suppress("UNCHECKED_CAST")
    fun violationsOf(entry: Any?): List<Map<String, Any?>>? =
        ((entry as? Map<*, *>)?.get("violations") as? List<*>)?.map { it as Map<String, Any?> }

    /**
     * Is the entry the publisher's shape for its own document — one typed cable
     * per wire and a violations list? A raw `/blackboard/assert` can put a
     * document with no cables on the board; that is reconciled on first load.
     */
    fun isReconciled(entry: Any?): Boolean {
        val m = entry as? Map<*, *> ?: return false
        val program = programOf(m) ?: return false
        val cables = m["cables"] as? List<*> ?: return false
        return m["violations"] is List<*> && cables.size == program.wires.size &&
            cables.all { (it as? Map<*, *>)?.containsKey("type") == true }
    }
}
