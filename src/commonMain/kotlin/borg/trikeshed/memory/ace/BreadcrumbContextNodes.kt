package borg.trikeshed.memory.ace

import borg.trikeshed.agent.modelmux.*
import borg.trikeshed.agent.modelmux.acp.*
import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.reifyStrict

/** The ordinary LCNC context path over retained local source and the existing lexical projection. */
object BreadcrumbContextNodes {
    const val PROJECT = "context.project"
    const val RECALL = "context.recall"

    fun registry(cas: CasStore, breadcrumbs: BreadcrumbElement): Map<String, LcncNodeRunner> = _m[
        PROJECT j LcncNodeRunner { node, inputs ->
            val source = VmRuntimeNodes.string(node, inputs, "source")
            require(source.length <= BreadcrumbContext.CONTENT_LIMIT) { "Source exceeds breadcrumb capacity" }
            val question = VmRuntimeNodes.string(node, inputs, "question")
            require(question.length <= 256) { "Question exceeds 256 characters" }
            val session = session(node, inputs)
            val name = node.params["name"] ?: "source.txt"
            val ceiling = ceiling(node, "maxChars", 2_000)
            val scope = "lcnc/$session"
            val local = SharedContext(cas, policy = ContextReadPolicy(allowedScopes = _s[scope]),
                facets = breadcrumbs.facets ?: ProjectionFacets())
            val bytes = source.encodeToByteArray(throwOnInvalidSequence = true)
            val document = ManifestDocument.of(name, bytes)
            val sourceCid = local.admit(bytes)
            val manifest = BreadcrumbContextManifest.of(scope, BreadcrumbProjector.POLICY, "lcnc", _l.get(), _l[document])
            val revision = local.admit(manifest.encode())
            val snapshot = local.acquire(revision, scope)
            val projection = BreadcrumbProjector.project(snapshot, question, ProjectionBudget(maxChars = ceiling), local.facets)
            require(projection.excerpts.size > 0) { "No source line matches the question within the projection budget" }
            val summary = projection.render()
            val sourceId = BreadcrumbId("source-${sourceCid.hex}")
            val id = BreadcrumbId(ContentId.of("${BreadcrumbProjector.POLICY}:${revision.value}:$question:$summary".encodeToByteArray()).hex)
            val details = _m[
                "tier" j "heuristic", "model" j null, "policy" j projection.policy, "question" j question,
                "manifestCid" j revision.value, "sourceCid" j sourceCid.value,
                "charsUsed" j projection.charsUsed, "omittedCandidates" j projection.omittedCandidates,
                "droppedConstraints" j projection.droppedConstraints, "requiresExpansion" j projection.requiresExpansion,
                "rangeUnit" j "UTF-16 code units; end exclusive",
                "ranges" j projection.excerpts.view.map { excerpt -> _m[
                    "cid" j excerpt.documentCid.value, "start" j excerpt.range.start,
                    "end" j excerpt.range.end, "text" j excerpt.text,
                ] },
            ]
            breadcrumbs.transact(session) { context ->
                context.put(sourceId, "document", source, title = name)
                val record = context.put(id, "projection:${projection.policy}", summary, children = _l[sourceId])
                val indexed = requireNotNull(context.index[record.id])
                val original = requireNotNull(context.index[sourceId])
                context.access(record.id)
                cas.sync()
                _m[
                    "summary" j summary, "sourceCid" j sourceCid.value, "sourceRef" j original.ref.value, "cid" j indexed.cid.value,
                    "ref" j indexed.ref.value, "breadcrumbId" j id.value, "sourceId" j sourceId.value,
                    "context" j session, "manifestCid" j revision.value,
                    "requiresExpansion" j projection.requiresExpansion, "projection" j details,
                ]
            }
        },
        RECALL j LcncNodeRunner { node, inputs ->
            val session = session(node, inputs)
            val reference = VmRuntimeNodes.string(node, inputs, "ref")
            val cid = VmRuntimeNodes.string(node, inputs, "cid")
            val question = VmRuntimeNodes.string(node, inputs, "question", default = "", allowBlank = true)
            require(question.length <= 256) { "Question exceeds 256 characters" }
            val presence = node.params["presence"]?.toDoubleOrNull() ?: if ("presence" in node.params)
                throw IllegalArgumentException("Presence must be a number from zero to one") else 1.0
            val extent = BreadcrumbPresence(presence)
            val budget = BreadcrumbBudget(contextTokens = ceiling(node, "budgetChars", 6_144) / 4,
                instructionTokens = 0, outputTokens = 0)
            breadcrumbs.transact(session, create = false) { context ->
                val result = requireNotNull(BreadcrumbToolDispatcher(context, budget).dispatch(TOOL_RECALL,
                    _m["ref" j reference, "cid" j cid, "presence" j extent.value.toString(), "query" j question]))
                val envelope = reifyStrict(result) as? Map<*, *> ?: error("Recall returned no metadata")
                require("error" !in envelope && envelope["id"] is String) { envelope["error"]?.toString() ?: "Recall budget cannot carry reference metadata" }
                val ranges = (envelope["ranges"] as? List<*>).orEmpty()
                val text = buildString {
                    var previousCid: String? = null
                    var previousEnd: Int? = null
                    for (value in ranges) {
                        val range = value as? Map<*, *> ?: error("Recall range has no metadata")
                        val cid = range["cid"] as? String ?: error("Recall range has no content identity")
                        val start = (range["start"] as? Number)?.toInt() ?: error("Recall range has no start")
                        if (isNotEmpty() && (cid != previousCid || start != previousEnd)) append("\n…\n")
                        append(range["content"] as? String ?: error("Recall range has no text"))
                        previousCid = cid
                        previousEnd = (range["end"] as? Number)?.toInt() ?: error("Recall range has no end")
                    }
                }
                val sources = (envelope["provenance"] as? List<*>).orEmpty().map {
                    (it as? Map<*, *>)?.get("cid") as? String ?: error("Recall provenance has no content identity")
                }.distinct()
                _m["recall" j envelope, "text" j text, "context" j session, "sourceCids" j sources]
            }
        },
    ]

    private fun session(node: LcncNode, inputs: Map<String, Any?>): String =
        VmRuntimeNodes.string(node, inputs, "context").also {
            require(it.length <= 128 && SharedContextSettings.isScopeName(it)) { "Context must be 1..128 printable characters" }
        }

    private fun ceiling(node: LcncNode, name: String, default: Int): Int =
        (node.params[name]?.toIntOrNull() ?: if (name in node.params)
            throw IllegalArgumentException("$name must be a whole number") else default).also {
            require(it in 128..64_000) { "$name must be 128..64000 characters" }
        }
}
