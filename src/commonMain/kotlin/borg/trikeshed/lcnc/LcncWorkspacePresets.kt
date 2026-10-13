package borg.trikeshed.lcnc

import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.vm.VmLanguage

/** Domain examples compose the canonical curator; they do not own another extraction pipeline. */
object LcncWorkspacePresets {
    const val MEDICAL = "preset-medical-curation"
    const val LEGAL = "preset-legal-curation"
    const val POLYGLOT = "preset-polyglot-camel"
    const val DOCUMENT_REVIEW = "preset-document-review"
    const val DOCUMENT_CORRECTION = "preset-document-correction"

    val catalog: List<LcncPresetInfo> = listOf(
        LcncPresetInfo(MEDICAL, "Medical record tuple review",
            "Curates a synthetic care-team transcript into source-linked sentences, tokens and proposed relations.",
            "A configured document curator, Stanford parser and model provider key. Use synthetic records only.",
            "Nested evidence sheets, rule candidates and hypothetical Rete firings for human review; no clinical decision or treatment actuation.",
            "The source transcript; distinguish each speaker's report from an accepted fact."),
        LcncPresetInfo(LEGAL, "Legal record tuple review",
            "Curates a synthetic contract-review transcript while retaining each speaker's source quotations.",
            "A configured document curator, Stanford parser and model provider key. Use synthetic records only.",
            "Nested evidence sheets and hypothetical Rete firings, without admitting rules or issuing legal decisions.",
            "The source transcript; compare the parties' claims before admitting any rule."),
        LcncPresetInfo(POLYGLOT, "Typed function and local route",
            "Transforms a structured review message in a temporary language guest and carries it through a local Camel route.",
            "An available source language and installed Camel module. The gallery default uses JavaScript; the language facet offers other installed languages. No model provider key is needed.",
            "The transformed value and the reply from the route; both are ordinary construction outputs.",
            "Choose a language in the language facet, then edit the input or function body before running."),
        LcncPresetInfo(DOCUMENT_REVIEW, "Retained document review tasks",
            "Inspects a retained medical or legal curation result and creates ordinary board tasks for its document-quality issues.",
            "A retained document receipt and the local board. No new parser or model call is made.",
            "The source-linked evidence sheet and new or existing review tasks; no rule admission or clinical or legal decision.",
            "The receipt parameter. Choose Run explicitly to create tasks; inspecting a receipt alone does not create them."),
        LcncPresetInfo(DOCUMENT_CORRECTION, "Correct and re-curate a document task",
            "Retains a reviewed source correction, re-runs the canonical curator, and reconciles the matching board task with that result.",
            "An existing document task, its current revision and receipt, and the configured document curator and model provider.",
            "The correction lineage, fresh evidence, and completed or still-open tasks. A model answer alone does not approve the document.",
            "The source object: supply corrected text, or leave it empty to retry the retained source. Confirm the task revision before Run."),
    )

    fun documents(): Map<String, String> = mapOf(
        MEDICAL to curation(MEDICAL, "synthetic-care-team.txt",
            "Synthetic training record, not clinical guidance.\n" +
                "Patient A reports that a visit summary is missing.\n" +
                "The nurse requests the original visit record.\n" +
                "The physician says that the missing record delays review.\n" +
                "The reviewer states that no treatment change is authorized by this transcript.\n" +
                "Synthetic conflicting workflow claims follow.\nDelays cause cancellations.\nDelays do not cause cancellations.",
            "Delays", listOf("patient", "nurse", "physician")),
        LEGAL to curation(LEGAL, "synthetic-contract-review.txt",
            "Synthetic training record, not legal advice.\n" +
                "Party A reports that the contract appendix is missing.\n" +
                "Party B says that the appendix was delivered.\n" +
                "The reviewer requests the delivery record.\n" +
                "The reviewer says that conflicting reports delay approval.\n" +
                "No legal conclusion is authorized by this transcript.\n" +
                "Synthetic conflicting workflow claims follow.\nDisputes cause delays.\nDisputes do not cause delays.",
            "Disputes", listOf("party-a", "party-b", "reviewer")),
        POLYGLOT to LcncProgramConfix.toJson(polyglot(VmLanguage.JAVASCRIPT)),
        DOCUMENT_REVIEW to LcncProgramConfix.toJson(review()),
        DOCUMENT_CORRECTION to LcncProgramConfix.toJson(correction()),
    )

    /** Explicit local actuation of a retained result; the original domain simulations stay read-only. */
    fun review(receiptCid: String = ""): LcncProgram = LcncProgram(DOCUMENT_REVIEW, s_[
        LcncNode("receipt", LcncContracts.SCOPE_IN, mapOf("name" to "receiptCid", "kind" to "id", "default" to receiptCid), x = 20.0, y = 50.0),
        LcncNode("sheet", "document.sheet", x = 350.0, y = 50.0),
        LcncNode("evidence", "sheet.concentric", x = 680.0, y = 50.0),
        LcncNode("tasks", DocumentCurationTasks.TASKS, x = 350.0, y = 440.0),
        LcncNode("review", "note", mapOf("text" to "Run explicitly to create document-quality tasks from this exact retained receipt.\n" +
            "No new NLP or model request is made. Re-running the same receipt does not duplicate tasks.\n" +
            "Open a task's correction construction to retry or correct its source. This does not admit rules, authorize treatment, or issue a legal decision."), x = 20.0, y = 440.0),
    ], s_[
        LcncWire("receipt", "value", "sheet", "receiptCid?"),
        LcncWire("sheet", "sheet", "evidence", "board"),
        LcncWire("receipt", "value", "tasks", "receiptCid"),
    ])

    /** The authored graph carries the admitted correction's source and recipe through the one curator. */
    fun correction(taskId: String = "", expectedRevision: String = "", priorReceiptCid: String = "",
                   source: Map<String, Any?> = emptyMap()): LcncProgram = LcncProgram(DOCUMENT_CORRECTION, s_[
        LcncNode("source", "json.value", mapOf("value" to jsonOf(source)), x = 20.0, y = 50.0),
        LcncNode("correction", DocumentCurationTasks.CORRECT, mapOf("taskId" to taskId,
            "expectedRevision" to expectedRevision, "priorReceiptCid" to priorReceiptCid), x = 350.0, y = 50.0),
        LcncNode("curate", "document.curate", x = 680.0, y = 50.0),
        LcncNode("evidence", "sheet.concentric", x = 1020.0, y = 50.0),
        LcncNode("tasks", DocumentCurationTasks.TASKS, x = 1020.0, y = 470.0),
        LcncNode("review", "note", mapOf("text" to "Inspect the task, revision and prior receipt before Run.\n" +
            "An empty source object retries the retained text; {\"text\":\"…\"} supplies a reviewed correction without replacing the original document. Do not rewrite valid source merely to satisfy a model.\n" +
            "The curator uses the task's original instructions. Only its retained result can reconcile the admitted task revision.\n" +
            "A stale revision or changed recipe is refused. A failed re-curation leaves the correction retained and the task unresolved; no rule admission or external actuation occurs."), x = 20.0, y = 430.0),
    ], s_[
        LcncWire("source", "value", "correction", "source"),
        LcncWire("correction", "source", "curate", "source?"),
        LcncWire("correction", "instructions", "curate", "instructions?"),
        LcncWire("curate", "sheet", "evidence", "board"),
        LcncWire("curate", "sheets", "evidence", "byStatus?"),
        LcncWire("curate", "receiptCid", "tasks", "receiptCid"),
    ])

    /** One composition, authored in the chosen language; nothing is compiled, installed or run here. */
    fun polyglot(language: VmLanguage, name: String = POLYGLOT): LcncProgram {
        val source = when (language) {
            VmLanguage.JAVASCRIPT -> "return {...input, review: 'human-required'}"
            VmLanguage.JAVA -> "var result = new LinkedHashMap<Object, Object>((Map<?, ?>) input);\n" +
                "result.put(\"review\", \"human-required\");\nreturn result;"
            VmLanguage.CLOJURE -> "(assoc input \"review\" \"human-required\")"
            VmLanguage.GROOVY -> "return input + [review: 'human-required']"
            VmLanguage.RUBY -> "return input.merge(\"review\" => \"human-required\")"
            VmLanguage.PYTHON -> "return dict(input, review=\"human-required\")"
            VmLanguage.KOTLIN_COMMON -> "return (input as Map<String, Any?>) + (\"review\" to \"human-required\")"
        }
        return LcncProgram(name, s_[
            LcncNode("source", "json.value", mapOf("value" to """{"case":"synthetic-review","approved":false}"""), x = 20.0, y = 50.0),
            LcncNode("function", "vm.polyglot", language.params(source) +
                mapOf("inputKind" to "OBJECT", "outputKind" to "OBJECT"), x = 330.0, y = 50.0),
            LcncNode("route", "vm.camel", mapOf("from" to "direct:lcnc-review", "to" to "log:lcnc-review", "reach" to "LOCAL"), x = 670.0, y = 50.0),
            LcncNode("value", "display", x = 330.0, y = 450.0),
            LcncNode("reply", "display", x = 980.0, y = 50.0),
        ], s_[
            LcncWire("source", "value", "function", "input?"),
            LcncWire("function", "value", "route", "messages?"),
            LcncWire("function", "value", "value", "x"),
            LcncWire("route", "routed", "reply", "x"),
        ])
    }

    fun curation(name: String, file: String, text: String, subject: String = "", parties: List<String> = emptyList()): String = LcncProgramConfix.toJson(LcncProgram(name, s_[
        LcncNode("source", "json.value", mapOf("value" to jsonOf(mapOf("name" to file, "mediaType" to "text/plain", "text" to text))), x = 20.0, y = 50.0),
        LcncNode("curate", "document.curate", x = 350.0, y = 50.0),
        LcncNode("evidence", "sheet.concentric", x = 680.0, y = 50.0),
        LcncNode("record", "display", x = 350.0, y = 400.0),
        LcncNode("candidates", "display", x = 680.0, y = 500.0),
        LcncNode("assumptions", "json.value", mapOf("value" to jsonOf(parties.map { party ->
            mapOf("subject" to subject, "obj" to party, "party" to party, "hypothetical" to true)
        })), x = 1020.0, y = 50.0),
        LcncNode("simulation", "nars.reteFire", mapOf("discount" to "0.5"), x = 1020.0, y = 390.0),
        LcncNode("firings", "display", x = 1360.0, y = 390.0),
        LcncNode("review", "note", mapOf("text" to "Synthetic multi-party record. Review quotations, token spans and dependencies in the evidence sheet.\n" +
            "Rule candidates are proposals, not admitted causal facts. No automatic nal.rule.admit, model decision or external actuation is wired.\n" +
            "The assumptions node supplies hypothetical party states, not observations or votes. After the first run, edit them and Run from assumptions to reuse the retained candidates without re-curating.\n" +
            "Both positive and negative support remain visible. A firing is dependent support, not agreement, approval or external verification.\n" +
            "Use the Document surface for the retained record and the blackboard for downstream observations."), x = 20.0, y = 480.0),
    ], s_[
        LcncWire("source", "value", "curate", "source?"),
        LcncWire("curate", "sheet", "evidence", "board"),
        LcncWire("curate", "sheets", "evidence", "byStatus?"),
        LcncWire("curate", "record", "record", "x"),
        LcncWire("curate", "ruleCandidates", "candidates", "x"),
        LcncWire("curate", "ruleCandidates", "simulation", "rules?"),
        LcncWire("assumptions", "value", "simulation", "assertions?"),
        LcncWire("simulation", "firings", "firings", "x"),
    ]))
}
