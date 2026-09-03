package borg.trikeshed.graal

/**
 * BlackboardNamespaces — the first enumeration of the daemon blackboard's key
 * prefixes, and the DEFAULT admit list for [BlackboardChangesFactElement].
 *
 * The blackboard has never had a schema: every writer picks a `prefix/…` key
 * ad hoc and the readers (BlackboardWire, graal.html, the LCNC blackboard
 * nodes) carry tribal lists of what those prefixes mean. This object is a data
 * object in the plain sense — a table, not code — so the admit list can change
 * by editing a row rather than a predicate, and every prefix has its meaning
 * and its producer written next to it.
 *
 * Admission rule: a key is admitted into the `blackboard` fact partition unless
 * one of [excludedByDefault] is a prefix of it. The three excluded prefixes are
 * the OUTPUTS of rule firing — receipts the production sink and the NARS
 * curation write back onto the blackboard. Admitting them would make every
 * firing a fresh fact, every fresh fact a rule evaluation, and every evaluation
 * a firing: the blackboard → rete → sink → blackboard loop the merger brief
 * names as its first hazard. They stay receipt-only on the board (readable
 * through `/blackboard/board`, never watchable through a production).
 */
data object BlackboardNamespaces {

    /** One row of the table: a key prefix, what lives under it, and who writes it. */
    data class Namespace(
        val prefix: String,
        val meaning: String,
        val producer: String,
        /** False for rule-firing outputs; these never enter the fact plane by default. */
        val admitted: Boolean = true,
    )

    /**
     * Every prefix a `blackboard.put` in the source tree writes today, longest
     * prefix first so [namespaceOf] resolves `narsese/curation/` before
     * `narsese/`. Add a row when a new writer appears; flip `admitted` to false
     * for anything a production's firing produces.
     */
    val known: List<Namespace> = listOf(
        Namespace("narsese/curation/", "belief-bag curation receipts, one per (kind, angular) reviewed downstream of a rule firing", "BoardReviewBridge via the production sink", admitted = false),
        Namespace("narsese/rete/firing/", "NARS rete firing receipts keyed by firing cid", "OroborosDaemon narsese firing collector", admitted = false),
        Namespace("kanban/rule/", "production-sink activation receipts: bindings + salience per (ruleId, activationId)", "KanbanModule productionSink", admitted = false),
        Namespace("kanban/committed/", "board store commit receipts per (jobId, sequence)", "KanbanModule committed collector"),
        Namespace("kanban/review/", "kanban-nars review gloss per angular", "KanbanModule review bridge"),
        Namespace("kanban/drift/", "board drift observations stamped by the module clock", "KanbanModule ticker"),
        Namespace("kanban/", "kanban module surface (anything not under a narrower kanban/ row)", "KanbanModule"),
        Namespace("lcnc/program/", "typed program entries {name, document, cables, violations, sourceCid} per canvas", "LcncPublisher.publishProgram"),
        Namespace("lcnc/vocabulary", "the node-type vocabulary the canvases are built from", "LcncPublisher.publishAll"),
        Namespace("lcnc/", "LCNC surface (anything not under a narrower lcnc/ row)", "LcncPublisher"),
        Namespace("pointcut-def/", "pointcut definitions written through the assert funnel; enabled=false suppresses a runtime site", "PointcutDefinitionWriter.applyFunnelDoc"),
        Namespace("pointcut/", "pointcut landings keyed pointcut/<typedef>/<method>/<siteIdx>, value is a PointcutLanding", "PointcutBlackboardAdapter.put (Hypervisor and daemon adapters)"),
        Namespace("hermes/python/pointcut/import/", "GraalPy import-site pointcuts observed in the Hermes sleeve", "HermesPythonPort"),
        Namespace("hermes/python/gap/", "gaps between the Hermes source root and the sleeve, per root", "HermesPythonPort"),
        Namespace("hermes/python/pen/", "pen verbs delegated to the Hermes sleeve, per verb and ordinal", "HermesPenDelegates"),
        Namespace("hermes/console/signal/", "console signals raised by the Hermes xterm session", "HermesVmConsole"),
        Namespace("hermes/wiki-trainer/", "wiki trainer corpus roots", "HermesWikiTrainerCorpus"),
        Namespace("hermes/", "Hermes surface (anything not under a narrower hermes/ row)", "Hermes bridges"),
        Namespace("construction-kif/", "KIF minted by the construction reader, keyed by content cid", "OroborosDaemon reader lane"),
        Namespace("nal-kif/", "KIF minted from NAL, keyed by content cid", "OroborosDaemon nal.mint lane"),
        Namespace("legal-kif/", "KIF ingested from legal text or recorded by the council, keyed by content cid", "OroborosDaemon legal.ingest and council.record lanes"),
        Namespace("kif-ledger/", "durable KIF ledger entries mirrored from couch", "NarsDurableLedger thaw"),
        Namespace("rete-rule/", "rete rule definitions keyed by rule cid", "OroborosDaemon rule lane"),
        Namespace("council-case/", "council case documents per caseId", "CouncilNodes"),
        Namespace("hook-intake/", "webhook intake receipts per (program, node, port, nuid)", "CouchWebhookBindings"),
        Namespace("hook-run/", "webhook run receipts per (program, node, port, nuid), status field carries the outcome", "CouchWebhookBindings"),
        Namespace("hook-receipt/", "webhook delivery receipts per (subscription, nuid)", "CouchChangesWebhookBridge"),
        Namespace("hook-delivery-out/", "outbound hook delivery ledger entries", "CausalHookDeliveryLedger"),
        Namespace("hook-delivery/", "inbound hook delivery ledger entries", "CausalHookDeliveryLedger"),
        Namespace("context-receipt/", "ACE context chunk receipts keyed by chain head", "AceContextNodes"),
        Namespace("module/", "module supervisor receipts: attached, detached, attach-failed", "ModuleSupervisor"),
        Namespace("daemon/", "daemon boot and index receipts (daemon/boot/*, daemon/linecas-index)", "OroborosDaemon"),
        Namespace("oroboros/", "reserved for daemon-lane receipts; no writer in the source tree today", "none yet"),
        Namespace("narsese/", "NARS surface (anything not under a narrower narsese/ row)", "OroborosDaemon narsese lanes"),
    )

    /** Prefixes that never enter the fact plane by default: the rule-firing outputs (the loop surface). */
    val excludedByDefault: List<String> = known.filter { !it.admitted }.map { it.prefix }

    /** The default admit predicate for [BlackboardChangesFactElement]: true unless an excluded prefix starts the key. */
    fun admitByDefault(key: String): Boolean {
        for (p in excludedByDefault) if (key.startsWith(p)) return false
        return true
    }

    /** The most specific known row for a key, or null for a key no row describes. */
    fun namespaceOf(key: String): Namespace? {
        var best: Namespace? = null
        for (n in known) if (key.startsWith(n.prefix) && (best == null || n.prefix.length > best.prefix.length)) best = n
        return best
    }
}
