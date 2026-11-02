package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.channels.spi.JvmProcessProtocolPipes
import borg.trikeshed.userspace.nio.process.ProcessCaptureChannel
import kotlinx.coroutines.*
import java.nio.file.Files
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
    val binary: Path = binary.toRealPath()
    val adapter: Path = adapter.toRealPath()
    val datalogDirectory: Path = datalogDirectory.toRealPath()
    val environment: Map<String, String> = environment.toMap()
    init {
        require(binarySha256.matches(Regex("[0-9a-f]{64}")))
        require(timeoutMillis in 1..45_000)
    }
    internal fun check() {
        require(Files.isExecutable(binary) && sha256(binary) == binarySha256) { "Native executable pin differs" }
        require(sha256(adapter) == ADAPTER_SHA256) { "Native adapter pin differs" }
        require(sha256(datalogDirectory.resolve("datalog.nix")) == DATALOG_SHA256) { "Native datalog pin differs" }
    }
    companion object {
        const val ADAPTER_SHA256 = "e5c5a5df8b465dd93cdc18a4081df1aea0a6cec0049b3834cbdf5b13cfe38c3c"
        const val DATALOG_SHA256 = "9953ebeb4c4792817f49ff00ff16a4fb82945ff44b2778eecb7f3651e4e7fdd7"
        internal fun sha256(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val bytes = ByteArray(8192)
                while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** One finite capture per admitted review request; its owning wire drains requests before retirement. */
class NativeHornQuery(private val profile: NativeHornProfile) {
    suspend fun report(review: CurationReview, cas: CasStore, snapshot: ContentId, revision: Long,
                       goal: String): Map<String, Any?> {
        val before = ReviewedHornPlan.report(review, cas, snapshot, revision, goal)
        val result = execute(before)
        // Rechecks current source/ontology/review lineage and exact quotation CAS identities after execution.
        val after = ReviewedHornPlan.report(review, cas, snapshot, revision, goal)
        for (key in s_["readCid", "decisionCid", "approvalCid", "inputCid", "frameId"].view)
            require(before[key] == after[key]) { "Reviewed material changed during native query" }
        return result + _m["snapshotCid" j before["snapshotCid"], "readCid" j before["readCid"],
            "decisionCid" j before["decisionCid"], "approvalCid" j before["approvalCid"],
            "worldObligationApplicability" j "UNKNOWN", "authority" j "reviewed-read-only-not-a-sink-permit",
            "nativeBinarySha256" j profile.binarySha256, "nativeAdapterSha256" j NativeHornProfile.ADAPTER_SHA256,
            "wallLimitMillis" j profile.timeoutMillis, "outputLimitBytes" j OUTPUT_LIMIT,
            "memoryIsolationProvided" j false, "networkIsolationProvided" j false]
    }

    /** Only host-generated exports reach this boundary; internal for actual native fixture verification. */
    internal suspend fun execute(report: Map<String, Any?>): Map<String, Any?> {
        val input = (report["inputJson"] as String).encodeToByteArray(throwOnInvalidSequence = true)
        require(input.size <= INPUT_LIMIT) { "Native input exceeds 4MiB" }
        require(ContentId.of(input).value == report["inputCid"]) { "Native input identity differs" }
        val data = reifyMap(input.decodeToString(throwOnInvalidSequence = true))
        require(data["scope"] == "review-record-observations") { "Native query only accepts reviewed observations" }
        val directory = withContext(Dispatchers.IO) { Files.createTempDirectory("trikeshed-horn-") }
        val path = directory.resolve("input.json")
        try {
            withContext(Dispatchers.IO) {
                profile.check()
                Files.write(path, input)
                require(ContentId.of(Files.readAllBytes(path)).value == report["inputCid"])
            }
            val pipes = JvmProcessProtocolPipes.open(
                command = { _, _ -> _l[profile.binary.toString(), "instantiate", "--eval", "--strict", "--json",
                    "--readonly-mode", profile.adapter.toString(), "--argstr", "sumoNix", profile.datalogDirectory.toString(),
                    "--argstr", "input", path.toString()] },
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
            checkProof(result["proof"], data)
            withContext(Dispatchers.IO) {
                profile.check()
                require(ContentId.of(Files.readAllBytes(path)).value == report["inputCid"]) { "Native input changed" }
            }
            return result
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { Files.deleteIfExists(path); Files.deleteIfExists(directory) }
        }
    }

    private fun checkProof(proof: Any?, data: Map<String, Any?>) {
        val provenance = data["provenance"] as Map<*, *>
        val sources = ((provenance["facts"] as List<*>) + (provenance["rules"] as List<*>))
            .map { (it as Map<*, *>)["source"] }
        var count = 0
        fun visit(value: Any?, depth: Int) {
            if (value == null) return
            require(depth <= 64 && ++count <= 4096) { "Native proof limit" }
            val node = value as? Map<*, *> ?: error("Native proof node required")
            val witnesses = node["sources"] as? List<*> ?: error("Native proof sources required")
            require(witnesses.isNotEmpty() && witnesses.all { it in sources }) { "Native proof source differs" }
            if (node["asserted"] == true) require(node["witnessMode"] == "any-one-asserted-source")
            else for (child in node["from"] as? List<*> ?: error("Native proof premises required")) visit(child, depth + 1)
        }
        visit(proof, 0)
    }

    companion object {
        const val INPUT_LIMIT = 4 * 1024 * 1024
        const val OUTPUT_LIMIT = 1024 * 1024
    }
}

/** Strict parser also rejects duplicate keys and excessive nesting before recursive reification. */
private object DocumentResult {
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
            else -> true
        }
        require(unique(result)) { "Native JSON duplicate fields" }
        @Suppress("UNCHECKED_CAST")
        return result as Map<String, Any?>
    }
}
