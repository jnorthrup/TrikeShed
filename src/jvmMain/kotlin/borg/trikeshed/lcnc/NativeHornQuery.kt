package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.kif.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.channels.spi.JvmProcessProtocolPipes
import borg.trikeshed.userspace.nio.process.ProcessCaptureChannel
import kotlinx.coroutines.*
import borg.trikeshed.userspace.nio.file.spi.*
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Trusted bootstrap capability, never deserialized from a request. Libraries remain operator trusted. */
class NativeHornProfile(
    binary: Path,
    val binarySha256: String,
    adapter: Path,
    datalogDirectory: Path,
    environment: Map<String, String> = emptyMap(),
    val timeoutMillis: Long = 45_000,
) {
    val binary: Path = absolute(binary)
    val adapter: Path = absolute(adapter)
    val datalogDirectory: Path = absolute(datalogDirectory)
    val environment: Map<String, String> = environment.toMap()
    init {
        require(binarySha256.matches(Regex("[0-9a-f]{64}")))
        require(timeoutMillis in 1..45_000)
    }
    internal data class Resolved(val binary: String, val adapter: String, val datalog: String)
    internal fun check(files: FileOperations): Resolved {
        val paths = Resolved(files.canonicalPath(binary.toString()), files.canonicalPath(adapter.toString()),
            files.canonicalPath(datalogDirectory.toString()))
        require(sha256(files, paths.binary, 256 * 1024 * 1024) == binarySha256) { "Native executable pin differs" }
        require(sha256(files, paths.adapter, 16 * 1024) == ADAPTER_SHA256) { "Native adapter pin differs" }
        require(sha256(files, files.resolvePath(paths.datalog, "datalog.nix"), 1024 * 1024) == DATALOG_SHA256) { "Native datalog pin differs" }
        return paths
    }
    companion object {
        const val ADAPTER_SHA256 = "e5c5a5df8b465dd93cdc18a4081df1aea0a6cec0049b3834cbdf5b13cfe38c3c"
        const val DATALOG_SHA256 = "9953ebeb4c4792817f49ff00ff16a4fb82945ff44b2778eecb7f3651e4e7fdd7"
        private fun absolute(path: Path): Path {
            require(path.isAbsolute) { "Native profile paths must be absolute" }
            return path.normalize()
        }

        /** Pure, strict bootstrap parser. The caller owns config selection/read via FileOperations. */
        fun parse(text: String): NativeHornProfile {
            require(text.encodeToByteArray(throwOnInvalidSequence = true).size <= 16 * 1024) { "Native profile exceeds 16KiB" }
            val value = DocumentResult.parse(text)
            val required = _s["binary", "binarySha256", "adapter", "datalogDirectory"]
            require(required.all { it in value } && value.keys.all { it in required || it == "timeoutMillis" }) { "Unexpected or missing native profile fields" }
            fun path(key: String) = Path.of(value[key] as? String ?: error("Native profile $key must be text"))
            val timeout = if ("timeoutMillis" in value) {
                val n = (value["timeoutMillis"] as? Number)?.toDouble() ?: error("Native profile timeout must be numeric")
                require(n.isFinite() && n >= 1 && n <= 45_000 && n % 1 == 0.0) { "Native profile timeout out of bounds" }
                n.toLong()
            } else 45_000L
            return NativeHornProfile(path("binary"), value["binarySha256"] as? String ?: error("Native binary hash required"),
                path("adapter"), path("datalogDirectory"), timeoutMillis = timeout)
        }

        internal fun boundedRead(files: FileOperations, path: String, limit: Int): ByteArray {
            val reader = files as? BoundedFileReader
                ?: throw UnsupportedOperationException("Bounded file reads are unavailable")
            val fd = files.open(path)
            val size = try { files.size(fd) } finally { files.close(fd) }
            require(size in 0..limit.toLong()) { "Native profile file size limit" }
            return reader.readBounded(path, limit).also {
                require(it.size.toLong() == size && it.size <= limit) { "Native profile file changed or exceeds limit" }
            }
        }
        internal fun sha256(files: FileOperations, path: String, limit: Int): String =
            MessageDigest.getInstance("SHA-256").digest(boundedRead(files, path, limit)).joinToString("") { "%02x".format(it) }

    }
}

/** One finite capture per admitted review request; its owning wire drains requests before retirement. */
class NativeHornQuery(private val profile: NativeHornProfile) {
    /**
     * Explicit HOST assumptions, not a review/read permit or a public source admission path.
     * Every form of this one hash-checked manual source must cross the existing Horn exporter.
     * Normalized form ordinals are source references; they are not exact quotations or spans.
     */
    internal suspend fun manualTheory(revision: Long, source: KifSourceAssertion,
                                      query: String): Map<String, Any?> = withTimeoutOrNull(profile.timeoutMillis) {
        val input = manualInput(revision, source, query)
        val result = execute(input, "host-explicit-assertions")
        result + _m["kind" j "host-manual-horn-result/1", "policy" j MANUAL_POLICY,
            "source" j input["source"], "sourceForms" j input["sourceForms"],
            "authority" j "declared-host-manual-assumptions-only", "reviewAuthority" j "not-established",
            "currentness" j "not-checked", "worldObligationApplicability" j "UNKNOWN",
            "nativeBinarySha256" j profile.binarySha256, "nativeAdapterSha256" j NativeHornProfile.ADAPTER_SHA256,
            "wallLimitMillis" j profile.timeoutMillis, "outputLimitBytes" j OUTPUT_LIMIT,
            "memoryIsolationProvided" j false, "networkIsolationProvided" j false]
    } ?: error("Native manual query exceeded its overall wall limit")

    suspend fun report(review: CurationReview, cas: CasStore, snapshot: ContentId, revision: Long,
                       goal: String): Map<String, Any?> = withTimeoutOrNull(profile.timeoutMillis) {
        val before = ReviewedHornPlan.report(review, cas, snapshot, revision, goal)
        val result = execute(before)
        // Rechecks current source/ontology/review lineage and exact quotation CAS identities after execution.
        val after = ReviewedHornPlan.report(review, cas, snapshot, revision, goal)
        for (key in s_["readCid", "decisionCid", "approvalCid", "inputCid", "frameId"].view)
            require(before[key] == after[key]) { "Reviewed material changed during native query" }
        result + _m["snapshotCid" j before["snapshotCid"], "readCid" j before["readCid"],
            "decisionCid" j before["decisionCid"], "approvalCid" j before["approvalCid"],
            "worldObligationApplicability" j "UNKNOWN", "authority" j "reviewed-read-only-not-a-sink-permit",
            "nativeBinarySha256" j profile.binarySha256, "nativeAdapterSha256" j NativeHornProfile.ADAPTER_SHA256,
            "wallLimitMillis" j profile.timeoutMillis, "outputLimitBytes" j OUTPUT_LIMIT,
            "memoryIsolationProvided" j false, "networkIsolationProvided" j false]
    } ?: error("Native observation query exceeded its overall wall limit")

    /** Native positive taxonomy proof over the exact current, separately approved KIF selection. */
    suspend fun taxonomy(review: CurationReview, cas: CasStore, snapshot: ContentId, revision: Long,
                         predicate: String, subject: String, obj: String): Map<String, Any?> = withTimeoutOrNull(profile.timeoutMillis) {
        val before = ReviewedKifHornPlan.report(review, cas, snapshot, revision, predicate, subject, obj)
        val result = execute(before, "host-explicit-assertions")
        val after = ReviewedKifHornPlan.report(review, cas, snapshot, revision, predicate, subject, obj)
        for (key in s_["readCid", "decisionCid", "approvalCid", "inputCid", "frameId"].view)
            require(before[key] == after[key]) { "Reviewed KIF changed during native query" }
        result + _m[
            "kind" j "reviewed-kif-native-result/1", "coverage" j "positive-reviewed-selected-snapshot",
            "snapshotCid" j before["snapshotCid"], "readCid" j before["readCid"],
            "decisionCid" j before["decisionCid"], "approvalCid" j before["approvalCid"],
            "asOf" j after["asOf"], "reviewer" j before["reviewer"], "synthetic" j before["synthetic"],
            "policy" j before["policy"], "sourceEvidence" j before["sourceEvidence"], "hostRules" j before["hostRules"],
            "selectionCount" j before["selectionCount"], "selectionLimit" j before["selectionLimit"],
            "authority" j "reviewed-read-only-not-a-sink-permit", "nativeBinarySha256" j profile.binarySha256,
            "nativeAdapterSha256" j NativeHornProfile.ADAPTER_SHA256, "wallLimitMillis" j profile.timeoutMillis,
            "outputLimitBytes" j OUTPUT_LIMIT, "memoryIsolationProvided" j false, "networkIsolationProvided" j false]
    } ?: error("Native taxonomy query exceeded its overall wall limit")

    /** Each input remains an independently approved head; the union grants no sink authority. */
    suspend fun taxonomyUnion(review: CurationReview, cas: CasStore, references: List<CurationTaxonomyRef>,
                              predicate: String, subject: String, obj: String): Map<String, Any?> = withTimeoutOrNull(profile.timeoutMillis) {
        val before = ReviewedKifUnion.report(review, cas, references, predicate, subject, obj)
        val result = execute(before, "host-explicit-assertions")
        val after = ReviewedKifUnion.report(review, cas, references, predicate, subject, obj)
        for (key in s_["reads", "inputCid", "frameId"].view)
            require(before[key] == after[key]) { "Reviewed KIF changed during native query" }
        result + _m["kind" j "reviewed-kif-union-result/1",
            "coverage" j (if (before["projection"] == "complete-selection") "positive-reviewed-selected-snapshots" else "positive-reviewed-candidate-slice"),
            "reads" j before["reads"], "asOf" j after["asOf"], "policy" j before["policy"],
            "sourceEvidence" j before["sourceEvidence"], "hostRules" j before["hostRules"],
            "selectionCount" j before["selectionCount"], "selectionLimit" j before["selectionLimit"],
            "reviewedSelectionCount" j before["reviewedSelectionCount"], "projection" j before["projection"],
            "authority" j "reviewed-read-only-not-a-sink-permit", "nativeBinarySha256" j profile.binarySha256,
            "nativeAdapterSha256" j NativeHornProfile.ADAPTER_SHA256, "wallLimitMillis" j profile.timeoutMillis,
            "outputLimitBytes" j OUTPUT_LIMIT, "memoryIsolationProvided" j false, "networkIsolationProvided" j false]
    } ?: error("Native union query exceeded its overall wall limit")

    /** Compose native-checked proofs of original reviewed facts using the fixed host taxonomy rules. */
    suspend fun taxonomyChain(review: CurationReview, cas: CasStore, references: List<CurationTaxonomyRef>,
                              predicate: String, subject: String, obj: String): Map<String, Any?> {
        return withTimeoutOrNull(profile.timeoutMillis) {
                val before = ReviewedKifUnion.report(review, cas, references, predicate, subject, obj, search = true)
                val plan = before["chainPlan"] as? List<*> ?: error("No reviewed path within the chain limit")
                require(plan.size in 1..ReviewedKifUnion.CHAIN_SEGMENTS) { "Reviewed chain segment limit" }
                val segments = mutableListOf<Map<String, Any?>>()
                var next = subject
                for ((index, raw) in plan.withIndex()) {
                    val goal = raw as? Map<*, *> ?: error("Invalid reviewed chain segment")
                    val relation = goal["predicate"] as? String ?: error("Invalid reviewed chain predicate")
                    val start = goal["subject"] as? String ?: error("Invalid reviewed chain subject")
                    val end = goal["object"] as? String ?: error("Invalid reviewed chain object")
                    require(start == next && relation == (if (index == 0) predicate else "subclass")) {
                        "Reviewed chain does not compose"
                    }
                    val input = ReviewedKifUnion.report(review, cas, references, relation, start, end, search = true)
                    require(input["reads"] == before["reads"] && input["projection"] == "candidate-path" &&
                        (input["selectionCount"] as Int) in 1..ReviewedKifUnion.CHAIN_SEGMENT_EDGES) {
                        "Reviewed chain evidence changed"
                    }
                    val result = execute(input, "host-explicit-assertions")
                    require(result["status"] == "SUPPORTED") { "Native chain segment was not proved" }
                    segments.add(_m["query" j result["query"], "proof" j result["proof"],
                        "inputCid" j input["inputCid"], "frameId" j input["frameId"],
                        "sourceEvidence" j input["sourceEvidence"]])
                    next = end
                }
                require(next == obj) { "Reviewed chain target differs" }
                val after = ReviewedKifUnion.report(review, cas, references, predicate, subject, obj, search = true)
                for (key in s_["reads", "inputCid", "frameId", "chainPlan"].view)
                    require(before[key] == after[key]) { "Reviewed KIF changed during native chain query" }
                _m["kind" j "reviewed-kif-composite-result/1", "status" j "SUPPORTED",
                    "coverage" j "positive-reviewed-native-segment-composition", "query" j _l[predicate, subject, obj],
                    "reads" j before["reads"], "asOf" j after["asOf"], "policy" j before["policy"],
                    "reviewedSelectionCount" j before["reviewedSelectionCount"], "segmentCount" j segments.size,
                    "proof" j _m["kind" j "reviewed-kif-native-chain/1",
                        "rule" j (if (predicate == "instance") "instance-inheritance-and-subclass-transitivity" else "subclass-transitivity"),
                        "segments" j segments], "hostRules" j before["hostRules"],
                    "authority" j "reviewed-read-only-not-a-sink-permit", "nativeBinarySha256" j profile.binarySha256,
                    "nativeAdapterSha256" j NativeHornProfile.ADAPTER_SHA256, "wallLimitMillis" j profile.timeoutMillis,
                    "outputLimitBytes" j OUTPUT_LIMIT, "memoryIsolationProvided" j false, "networkIsolationProvided" j false]
        } ?: error("Native chain query exceeded its overall wall limit")
    }

    /** Only host-generated exports reach this boundary; internal for actual native fixture verification. */
    internal suspend fun execute(report: Map<String, Any?>, expectedScope: String = "review-record-observations"): Map<String, Any?> {
        val input = (report["inputJson"] as String).encodeToByteArray(throwOnInvalidSequence = true)
        require(input.size <= INPUT_LIMIT) { "Native input exceeds 4MiB" }
        require(ContentId.of(input).value == report["inputCid"]) { "Native input identity differs" }
        val data = reifyMap(input.decodeToString(throwOnInvalidSequence = true))
        require(expectedScope in _s["review-record-observations", "host-explicit-assertions"] && data["scope"] == expectedScope) {
            "Native query scope differs"
        }
        val files = coroutineContext[FileOperations] ?: error("Native query unavailable: FileOperations capability missing")
        val directory = withContext(NonCancellable + fileIoContext) { files.createTempDir("trikeshed-horn-") }
        val path = files.resolvePath(directory, "input.json")
        try {
            val resolved = withContext(fileIoContext) {
                val checked = profile.check(files)
                files.write(path, input)
                require(ContentId.of(NativeHornProfile.boundedRead(files, path, INPUT_LIMIT)).value == report["inputCid"])
                checked
            }
            val pipes = JvmProcessProtocolPipes.open(
                command = { _, _ -> _l[resolved.binary, "instantiate", "--eval", "--strict", "--json",
                    "--readonly-mode", "--store", "dummy://", resolved.adapter, "--argstr", "sumoNix", resolved.datalog,
                    "--argstr", "input", path] },
                environment = profile.environment, mergeError = true)
            // stderr shares this same finite cap. Diagnostics that make stdout non-JSON cause refusal.
            val captured = ProcessCaptureChannel(coroutineContext, pipes, maxBytes = OUTPUT_LIMIT,
                timeoutMillis = profile.timeoutMillis, failOnOverflow = true).await()
            require(!captured.timedOut && captured.exit == 0 && captured.dropped == 0L) { "Native query unavailable: process failed or timed out" }
            val text = captured.bytes.decodeToString(throwOnInvalidSequence = true)
            val result = DocumentResult.parse(text)
            require(result["kind"] == "sumo-nix-positive-horn-result/1" &&
                result["sumoNixRevision"] == borg.trikeshed.kif.SumoNixBridge.SUMO_REVISION &&
                result["semantics"] == "positive-scalar-horn-first-proof/1" && result["ontology"] == null &&
                result["firstProofOnly"] == true) { "Native result contract differs" }
            for (key in s_["scope", "frameId", "revision", "query", "excluded"].view)
                require(result[key] == data[key]) { "Native result $key differs" }
            require(result["inputCid"] == report["inputCid"] && result["frameId"] == report["frameId"]) { "Native result identity differs" }
            require(result["status"] in _s["SUPPORTED", "UNKNOWN"]) { "Native result status differs" }
            require((result["status"] == "UNKNOWN") == (result["proof"] == null)) { "Native proof status differs" }
            val checked = checkProof(result["proof"], data)
            withContext(fileIoContext) {
                require(profile.check(files) == resolved) { "Native profile canonical paths changed" }
                require(ContentId.of(NativeHornProfile.boundedRead(files, path, INPUT_LIMIT)).value == report["inputCid"]) { "Native input changed" }
            }
            // The native certificate is retained unchanged. This sibling is a host projection of
            // the same certificate check, not a second search or an independent semantic oracle.
            return result + _m["checkedProof" j checked?.plus(_m["inputCid" j report["inputCid"]])]
        } finally {
            withContext(NonCancellable + fileIoContext) { files.deleteRecursively(directory) }
        }
    }

    /** Verify only the supplied certificate; no theorem search or replacement inference. */
    private fun checkProof(proof: Any?, data: Map<String, Any?>): Map<String, Any?>? {
        if (proof == null) return null
        fun atom(value: Any?): List<String> {
            val words = value as? List<*> ?: error("Native atom required")
            require(words.isNotEmpty() && words.all { it is String }) { "Native scalar atom required" }
            return words.map { it as String }
        }
        fun ground(value: Any?): List<String> {
            val decoded = KifSemantic.decode(value as? String ?: error("Native proof fact required")).formula
                as? KifFormula.Predicate ?: error("Native proof must be a positive ground fact")
            return _l[decoded.name] + decoded.arguments.map {
                when (it) {
                    is KifTerm.Constant -> it.token
                    is KifTerm.Literal -> it.spelling
                    else -> error("Native proof must be a scalar ground fact")
                }
            }
        }
        val provenance = data["provenance"] as Map<*, *>
        val factSources = provenance["facts"] as List<*>
        val ruleSources = provenance["rules"] as List<*>
        val rules = data["rules"] as List<*>
        val factsByAtom = factSources.map { it as Map<*, *> }.groupBy { atom(it["fact"]) }
        val sourcesByRule = ruleSources.map { it as Map<*, *> }.groupBy { it["id"] }
        val rulesById = buildMap<String, List<*>> {
            for ((index, value) in rules.withIndex()) {
                val rule = value as List<*>
                require(rule.size == 3 && rule[0] == "=>")
                require(put(atom(rule[2]).first() + "#${index + 1}", rule) == null)
            }
        }
        val steps = mutableListOf<Map<String, Any?>>()
        val sources = linkedMapOf<String, Any?>()
        val originalVariables = mutableMapOf<String, Set<String>>()
        fun references(witnesses: List<*>): List<String> = witnesses.map {
            val source = it as Map<*, *>
            val id = source["sourceNodeId"] as? String ?: error("Native source node identity required")
            val previous = sources.put(id, source)
            require(previous == null || previous == source) { "Native source node identity is ambiguous" }
            id
        }
        fun term(token: String): Map<String, Any?> {
            // The projection wrapper is longer than a valid short-predicate source atom.
            val parsed = KifSemantic.decode("(term $token)", KifDecodeLimits(maxChars = 65_543)).formula as KifFormula.Predicate
            return when (val value = parsed.arguments.single()) {
                is KifTerm.Constant -> _m["kind" j "constant", "token" j value.token]
                is KifTerm.Literal -> _m["kind" j "literal", "spelling" j value.spelling]
                else -> error("Native binding must be a ground scalar term")
            }
        }
        var count = 0
        fun visit(value: Any?, path: List<Int>, depth: Int): List<String> {
            require(depth <= 64 && ++count <= 4096) { "Native proof limit" }
            val node = value as? Map<*, *> ?: error("Native proof node required")
            val fact = ground(node["fact"])
            val witnesses = node["sources"] as? List<*> ?: error("Native proof sources required")
            require(witnesses.isNotEmpty()) { "Native proof witnesses required" }
            val step = linkedMapOf<String, Any?>()
            step["path"] = path
            step["normalForm"] = "(${fact.joinToString(" ")})"
            step["predicate"] = fact.first()
            step["arguments"] = fact.drop(1).map(::term)
            if (node["asserted"] == true) {
                require(node.keys == _s["fact", "asserted", "sources", "witnessMode"] &&
                    node["witnessMode"] == "any-one-asserted-source") { "Native asserted proof contract differs" }
                val eligible = factsByAtom[fact].orEmpty().map { it["source"] }
                require(witnesses.all { it in eligible }) { "Native leaf source does not assert this fact" }
                step["kind"] = "asserted"
                step["witnessMode"] = "any-one-asserted-source"
                step["sourceNodeIds"] = references(witnesses)
            } else {
                require(node.keys == _s["fact", "rule", "sources", "from"]) { "Native derived proof contract differs" }
                val id = node["rule"] as? String ?: error("Native proof rule required")
                val rule = rulesById[id] ?: error("Native proof rule identity differs")
                val eligible = sourcesByRule[id].orEmpty().map { it["source"] }
                require(eligible.isNotEmpty() && witnesses == eligible) { "Native rule source differs" }
                val antecedent = rule[1] as List<*>
                val body = if (antecedent.firstOrNull() == "and") antecedent.drop(1).map(::atom) else _l[atom(antecedent)]
                val children = node["from"] as? List<*> ?: error("Native proof premises required")
                require(children.size == body.size) { "Native proof premise count differs" }
                val ruleSources = references(witnesses)
                val bindings = mutableMapOf<String, String>()
                fun match(pattern: List<String>, actual: List<String>, bind: Boolean = true) {
                    require(pattern.size == actual.size) { "Native proof atom arity differs" }
                    for ((expected, observed) in pattern.zip(actual)) {
                        if (expected.startsWith('?')) {
                            val previous = bindings[expected]
                            if (previous == null) { require(bind) { "Native proof head variable is unbound" }; bindings[expected] = observed }
                            else require(previous == observed) { "Native proof shared variable differs" }
                        } else require(expected == observed) { "Native proof rule atom differs" }
                    }
                }
                for (i in body.indices) match(body[i], visit(children[i], path + i, depth + 1))
                match(atom(rule[2]), fact, bind = false)
                step["kind"] = "derived"
                step["rule"] = id
                step["ruleSourceNodeIds"] = ruleSources
                step["rulePattern"] = rule
                step["premises"] = body.indices.map { i -> _m["ordinal" j i, "path" j (path + i), "pattern" j body[i]] }
                step["bindings"] = bindings.entries.map { (variable, token) ->
                    // Exported variables retain their original spelling with the current bridge.
                    // A matching source occurrence is named explicitly, never a global variable.
                    val originals = ruleSources.filter { sourceId ->
                        variable in originalVariables.getOrPut(sourceId) {
                            KifSemantic.decode((sources.getValue(sourceId) as Map<*, *>)["text"] as String).freeVariables
                        }
                    }
                    _m["variable" j variable, "rule" j id, "ruleSourceNodeIds" j ruleSources,
                        "originalVariableSourceNodeIds" j originals, "term" j term(token)]
                }
            }
            steps.add(step)
            return fact
        }
        require(visit(proof, emptyList(), 0) == atom(data["query"])) { "Native proof root differs from query" }
        val checked = _m["kind" j "native-horn-checked-proof/1", "frameId" j data["frameId"],
            "query" j data["query"], "semantics" j data["semantics"], "firstProofOnly" j true,
            "sumoNixRevision" j data["sumoNixRevision"], "mlirNixRevision" j data["mlirNixRevision"],
            "referenceMode" j "normalized-export-atoms-and-rule-scoped-variables-not-source-spans",
            "steps" j steps, "sources" j sources,
            "verification" j "supplied-native-certificate-only-not-independent-inference"]
        require(jsonOf(checked).encodeToByteArray(throwOnInvalidSequence = true).size <= CHECKED_PROOF_LIMIT) {
            "Native checked proof projection limit"
        }
        return checked
    }

    companion object {
        const val INPUT_LIMIT = 4 * 1024 * 1024
        const val OUTPUT_LIMIT = 1024 * 1024
        const val CHECKED_PROOF_LIMIT = 16 * 1024 * 1024
        const val MANUAL_POLICY = "host-manual-whole-positive-horn/1"

        /** Pure preparation for trusted host fixture callers; no approval is inferred from text. */
        internal fun manualInput(revision: Long, source: KifSourceAssertion, query: String): Map<String, Any?> {
            require(source.review == KifReview.APPROVED) { "Manual theory requires explicit host assumptions" }
            require(source.id.length in 1..1024 && source.sourceRevision.length in 1..1024 && source.text.length <= 65_536) {
                "Manual source bound"
            }
            for (value in s_[source.id, source.sourceRevision].view) value.encodeToByteArray(throwOnInvalidSequence = true)
            val bytes = source.text.encodeToByteArray(throwOnInvalidSequence = true)
            require(bytes.size <= 256 * 1024 && ContentId.of(bytes) == source.sourceCid) { "Manual whole-source identity differs" }
            val decoded = KifSemantic.decodeAll(source.text)
            require(decoded.size in 1..32) { "Manual theory supports 1..32 complete forms" }
            val forms = decoded.mapIndexed { ordinal, form ->
                _m["kind" j "host-manual-horn-form/1", "sourceCid" j source.sourceCid.value,
                    "sourceRevision" j source.sourceRevision, "ordinal" j ordinal,
                    "normalForm" j form.syntax.toKifString()]
            }
            val assertions = forms.map { form ->
                val id = "manual-form:" + ContentId.of(CanonicalCbor.encodeMap(form)).hex
                KifSourceAssertion(id, source.sourceCid, source.sourceRevision, source.review, form["normalForm"] as String)
            }
            val export = SumoNixBridge.compile(revision, assertions, query)
            val data = reifyMap(export.inputJson)
            require((data["excluded"] as List<*>).isEmpty() &&
                (data["facts"] as List<*>).size + (data["rules"] as List<*>).size == decoded.size) {
                "Whole manual source is outside the positive Horn profile"
            }
            // Match the existing native adapter's lexical restrictions before any execution.
            val reserved = _s["and", "or", "not", "exists", "forall", "equal", "=>", "<=>", "iff", "implies", "equivalence",
                "modalAttribute", "believes", "knows", "desires", "holdsDuring", "holds", "necessarily", "possibly"]
            fun relation(raw: Any?) {
                val words = raw as List<*>
                require(words.first() !in reserved && words.all { !(it as String).startsWith('@') }) {
                    "Whole manual source contains a reserved native relation or term"
                }
            }
            (data["facts"] as List<*>).forEach(::relation)
            for (raw in data["rules"] as List<*>) {
                val rule = raw as List<*>
                val body = rule[1] as List<*>
                if (body.first() == "and") body.drop(1).forEach(::relation) else relation(body)
                relation(rule[2])
            }
            relation(data["query"])
            return _m["inputJson" j export.inputJson, "inputCid" j export.inputCid.value,
                "frameId" j export.frameId.value, "policy" j MANUAL_POLICY,
                "source" j _m["id" j source.id, "sourceCid" j source.sourceCid.value,
                    "sourceRevision" j source.sourceRevision, "text" j source.text],
                "sourceForms" j assertions.mapIndexed { i, assertion -> forms[i] + _m["id" j assertion.id] }]
        }
    }
}

/** Strict parser also rejects duplicate keys and excessive nesting before recursive reification. */
internal object DocumentResult {
    fun parse(text: String): Map<String, Any?> {
        var depth = 0; var string = false; var escape = false
        for (c in text) if (string) {
            if (escape) escape = false else if (c == '\\') escape = true else if (c == '"') string = false
        } else when (c) {
            '"' -> string = true
            '{', '[' -> { depth++; require(depth <= 128) { "Native JSON nesting limit" } }
            '}', ']' -> { depth--; require(depth >= 0) }
        }
        require(depth == 0 && !string)
        val result = reifyStrict(text) as? Map<*, *> ?: error("Native JSON object required")
        fun unique(value: Any?): Boolean = when (value) {
            is Map<*, *> -> { val keys = HashSet<Any?>(); value.entries.all { it.key is String && keys.add(it.key) && unique(it.value) } }
            is List<*> -> value.all(::unique)
            is String -> { value.encodeToByteArray(throwOnInvalidSequence = true); true }
            else -> true
        }
        require(unique(result)) { "Native JSON duplicate fields" }
        @Suppress("UNCHECKED_CAST")
        return result as Map<String, Any?>
    }
}
