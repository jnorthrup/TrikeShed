package borg.trikeshed.narsese

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.parse.reifyStrict
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Offline only. `prepare <manifest>` prints blind requests; `score <manifest> <predictions>`
 * compares actual supplied predictions to provisional labels. No system is invoked here.
 */
object LegalNarsEvaluationCli {
    private const val MAX_JSON = 1_048_576
    private const val MAX_EXCERPT = 16 * 1024 * 1024
    private const val MAX_TOTAL_EXCERPT = 64 * 1024 * 1024

    private fun readBounded(path: String, limit: Int): ByteArray = Files.newInputStream(Path.of(path)).use { input ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            require(n <= limit - out.size()) { "Input exceeds byte limit" }
            out.write(buf, 0, n)
        }
        out.toByteArray()
    }

    private fun json(path: String): Pair<ContentId, Map<*, *>> {
        val bytes = readBounded(path, MAX_JSON)
        val root = reifyStrict(bytes.decodeToString(throwOnInvalidSequence = true))
        fun unique(value: Any?) {
            when (value) {
                is Map<*, *> -> {
                    require(value.entries.map { it.key }.toSet().size == value.size) { "Duplicate JSON key" }
                    value.entries.forEach { unique(it.value) }
                }
                is List<*> -> value.forEach(::unique)
            }
        }
        unique(root)
        return ContentId.of(bytes) to (root as? Map<*, *> ?: error("Expected JSON object"))
    }

    private fun string(row: Map<*, *>, key: String) = row[key] as? String ?: error("Expected string: $key")
    private fun offset(row: Map<*, *>, key: String): Int {
        val value = (row[key] as? Number)?.toDouble() ?: error("Expected offset: $key")
        require(value.isFinite() && value >= 0 && value <= Int.MAX_VALUE && value % 1.0 == 0.0) { "Invalid offset: $key" }
        return value.toInt()
    }
    private fun witness(row: Map<*, *>, fallback: ContentId? = null): LegalEvalWitness = LegalEvalWitness(
        row["excerptCid"]?.let { ContentId(it as String) } ?: requireNotNull(fallback) { "Missing excerptCid" },
        offset(row, "begin"), offset(row, "end"), string(row, "quote"),
        LegalEvalWitnessRole.valueOf(string(row, "role")))

    private fun witnessFields(w: LegalEvalWitness): Map<String, Any?> = _m[
        "excerptCid" j w.excerptCid.value, "begin" j w.begin, "end" j w.end,
        "quote" j w.quote, "role" j w.role.name]

    @JvmStatic
    fun main(args: Array<String>) {
        println(jsonOf(report(args)))
    }

    /** Same bounded CLI operation without redirecting process-wide output for callers. */
    fun report(args: Array<String>): Map<String, Any?> {
        require(args.size in 2..3 && args[0] in setOf("prepare", "score") &&
            (args[0] == "prepare" && args.size == 2 || args[0] == "score" && args.size == 3)) {
            "Usage: LegalNarsEvaluationCli prepare <manifest.json> | score <manifest.json> <predictions.json>"
        }
        val (manifestCid, manifest) = json(args[1])
        require(manifest["kind"] == "provisional-legal-eval/1") { "Unsupported manifest" }
        val rawCases = manifest["cases"] as? List<*> ?: error("Expected cases")
        require(rawCases.size <= 1_024) { "Case limit" }
        val excerpts = LinkedHashMap<ContentId, ByteArray>()
        var totalExcerptBytes = 0
        val cases = rawCases.map { raw ->
            val row = raw as? Map<*, *> ?: error("Expected case object")
            val id = ContentId(string(row, "excerptCid"))
            val anchors = row["anchors"] as? List<*> ?: error("Expected anchors")
            require(anchors.size <= 64) { "Anchor limit" }
            val required = row["requiredAnchors"]?.let { value ->
                val values = value as? List<*> ?: error("Expected requiredAnchors array")
                require(values.size <= 64) { "Required anchor limit" }
                values.map { witness(it as? Map<*, *> ?: error("Expected required anchor object"), id) }
            }
            val c = LegalEvalCase(string(row, "name"), string(row, "proposition"), ContentId(string(row, "originalCid")),
                id, LegalEvalLabel.valueOf(string(row, "expected")), anchors.map { witness(it as Map<*, *>, id) }, required)
            // Missing or unreadable excerpts become invalid fixtures, never implicit predictions.
            if (id !in excerpts) runCatching {
                readBounded(string(row, "excerptPath"), minOf(MAX_EXCERPT, MAX_TOTAL_EXCERPT - totalExcerptBytes))
            }.getOrNull()?.let { excerpts[id] = it; totalExcerptBytes += it.size }
            c
        }
        var predictionCid: ContentId? = null
        val predictions = if (args[0] == "prepare") emptyList() else {
            val (inputCid, response) = json(args[2])
            predictionCid = inputCid
            require(response["kind"] == "legal-eval-predictions/1") { "Unsupported prediction format" }
            val values = response["predictions"] as? List<*> ?: error("Expected predictions")
            require(values.size <= 4_096) { "Prediction limit" }
            values.map { raw ->
                val row = raw as? Map<*, *> ?: error("Expected prediction object")
                val witnesses = row["witnesses"] as? List<*> ?: error("Expected witnesses")
                require(witnesses.size <= 64) { "Witness limit" }
                LegalEvalPrediction(ContentId(string(row, "propositionCid")), LegalEvalLabel.valueOf(string(row, "label")),
                    witnesses.map { witness(it as Map<*, *>) })
            }
        }
        val report = LegalNarsEvaluation.score(cases, excerpts, predictions)
        val result: Map<String, Any?> = if (args[0] == "prepare") {
            require(report.invalidFixtures == 0) { "A source excerpt or provisional annotation is invalid" }
            mapOf("kind" to "legal-eval-requests/1", "manifestCid" to manifestCid.value,
                "scope" to "excerpt only; original lineage claimed",
                "requests" to cases.map { c -> mapOf("name" to c.name, "propositionCid" to c.propositionCid.value,
                    "proposition" to c.proposition, "claimedOriginalCid" to c.claimedOriginalCid.value,
                    "excerptCid" to c.excerptCid.value, "excerpt" to excerpts.getValue(c.excerptCid).decodeToString(throwOnInvalidSequence = true)) })
        } else mapOf("kind" to "legal-eval-report/1", "manifestCid" to manifestCid.value, "predictionCid" to predictionCid?.value,
            "scope" to "provisional excerpt annotations; no legal truth or source authentication",
            "missing" to report.missing, "invalidFixtures" to report.invalidFixtures, "invalidPredictions" to report.invalidPredictions,
            "scored" to report.scored, "labelAgreements" to report.labelAgreements, "groundedAgreements" to report.groundedAgreements,
            "explicitUnknown" to report.rows.count { it.predicted == LegalEvalLabel.UNKNOWN },
            "explicitConflicted" to report.rows.count { it.predicted == LegalEvalLabel.CONFLICTED },
            "invalidWitnesses" to report.rows.count { it.witnessIntegrity == false },
            "unacceptedWitnesses" to report.rows.count { it.acceptedWitness == false },
            "labelAgreementRate" to if (report.scored == 0) null else report.labelAgreements.toDouble() / report.scored,
            "groundedAgreementRate" to if (report.scored == 0) null else report.groundedAgreements.toDouble() / report.scored,
            "requiredAnchorCoverageScope" to "exact annotated quote coverage; independent of label agreement; not proof validity or inference performance",
            "requiredAnchorCases" to report.requiredAnchorCases,
            "requiredAnchorComparableCases" to report.requiredAnchorComparableCases,
            "completeRequiredAnchorCoverageCases" to report.completeRequiredAnchorCoverageCases,
            "completeRequiredAnchorCoverageRate" to if (report.requiredAnchorComparableCases == 0) null else
                report.completeRequiredAnchorCoverageCases.toDouble() / report.requiredAnchorComparableCases,
            "rows" to report.rows.mapIndexed { index, it -> mapOf("name" to it.name, "propositionCid" to cases[index].propositionCid.value,
                "status" to it.status.name, "predicted" to it.predicted?.name,
                "labelAgrees" to it.labelAgrees, "witnessIntegrity" to it.witnessIntegrity,
                "acceptedWitness" to it.acceptedWitness, "groundedAgreement" to it.groundedAgreement,
                "requiredAnchorCoverage" to it.requiredAnchorCoverage?.let { coverage -> _m[
                    "requiredCount" j coverage.requiredCount, "acceptedQuotes" j coverage.acceptedQuotes,
                    "matchedCount" j coverage.matchedCount, "missingAnchors" j coverage.missingAnchors?.map(::witnessFields),
                    "complete" j coverage.complete] }) })
        return result
    }
}
