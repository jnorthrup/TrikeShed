package borg.trikeshed.narsese

import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.reifyStrict
import borg.trikeshed.rdf.*
import borg.trikeshed.userspace.nio.file.spi.StorageDurability
import kotlinx.coroutines.CancellationException

enum class KgRetentionIssue { MISSING_CAS, BOUNDED_READER_UNAVAILABLE, LIMIT, IDENTITY, PUT, SYNC, READBACK }

/** A failed import retention command admits no assertions and does not stop the bag owner. */
class KgRetentionRefused(val issue: KgRetentionIssue, cause: Throwable? = null) :
    IllegalArgumentException("KG retention refused: ${issue.name}", cause)

/** Bounded immutable payloads for the existing bag channel; this is not a second corpus owner. */
object KgRetention {
    const val MAX_SOURCE_CHARS = 65_536
    const val MAX_ARTIFACT_BYTES = 256 * 1024
    const val MAX_DEFINITIONS = 2048
    const val MAX_TOTAL_BYTES = 4 * 1024 * 1024
    const val MAX_ROLE_DEFINITIONS = 64
    const val ROLE_VOCAB = "urn:trikeshed:kg-role-selection:1:"

    /** Per-response bounded/hash-checked reads, shared with legacy source metadata inspection. */
    private class Readback(cas: CasStore?) {
        val reader = cas as? BoundedCasReader
        var limited = false
            private set
        private var remaining = MAX_TOTAL_BYTES
        private val blobs = mutableMapOf<ContentId, ByteArray?>()
        fun read(cid: ContentId): ByteArray {
            if (cid in blobs) return requireNotNull(blobs[cid])
            if (remaining <= 0) { limited = true; error("Source inspection limit") }
            val cap = minOf(MAX_ARTIFACT_BYTES, remaining)
            if (cap < MAX_ARTIFACT_BYTES) limited = true
            remaining -= cap; blobs[cid] = null
            val bytes = requireNotNull(reader?.getBounded(cid, cap))
            require(bytes.size <= cap && ContentId.of(bytes) == cid)
            blobs[cid] = bytes
            remaining += cap - bytes.size
            return bytes
        }
    }

    class Artifact(val cid: ContentId, bytes: ByteArray) {
        private val content = bytes.also {
            if (it.size > MAX_ARTIFACT_BYTES) throw KgRetentionRefused(KgRetentionIssue.LIMIT)
        }.copyOf()
        val size: Int get() = content.size
        fun bytes(): ByteArray = content.copyOf()
    }

    class Batch(val source: Artifact, definitions: Series<Artifact>) {
        // Capture the oracle before channel admission; a caller cannot replace later elements.
        private val entries = run {
            if (definitions.size !in 0..MAX_DEFINITIONS) throw KgRetentionRefused(KgRetentionIssue.LIMIT)
            val captured = ArrayList<Artifact>(definitions.size)
            for (artifact in definitions.view) captured.add(artifact)
            captured.toSeries()
        }
        val definitions: Series<Artifact> get() = entries
        val artifacts: Series<Artifact> get() = (entries.size + 1) j { if (it == 0) source else entries[it - 1] }
    }

    fun prepare(text: String, mapped: List<KgNalBridge.NalMapped>): Batch {
        if (text.length > MAX_SOURCE_CHARS || mapped.size > MAX_DEFINITIONS)
            throw KgRetentionRefused(KgRetentionIssue.LIMIT)
        val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
        if (bytes.size > MAX_ARTIFACT_BYTES) throw KgRetentionRefused(KgRetentionIssue.LIMIT)
        val source = Artifact(ContentId.of(bytes), bytes)
        val definitions = LinkedHashMap<ContentId, Artifact>()
        var total = source.size.toLong()
        for (statement in mapped) {
            val definition = statement.propositionBytes(source.cid.value)
            if (definition.size > MAX_ARTIFACT_BYTES) throw KgRetentionRefused(KgRetentionIssue.LIMIT)
            val cid = ContentId.of(definition)
            if (cid !in definitions) {
                total += definition.size
                if (total > MAX_TOTAL_BYTES) throw KgRetentionRefused(KgRetentionIssue.LIMIT)
                definitions[cid] = Artifact(cid, definition)
            }
        }
        return Batch(source, definitions.values.toSeries())
    }

    /** Per-response metadata only: at most 64 definitions and 4MiB of unique bounded CAS reads. */
    fun sourceContexts(cas: CasStore?): (SemanticSignal?) -> Map<String, Any?> {
        val readback = Readback(cas)
        var inspected = 0
        val sources = mutableMapOf<ContentId, Map<ContentId, KgNalBridge.NalMapped>>()
        return inspect@{ signal ->
            val pid = signal?.propositionCid
            fun result(status: String, source: String? = null): Map<String, Any?> = _m[
                "propositionCid" j pid, "sourceContext" j source, "sourceContextStatus" j status]
            if (signal == null) return@inspect result("resident-unavailable")
            if (pid == null) return@inspect result("legacy-unresolved")
            if (readback.reader == null) return@inspect result("unavailable-or-unverified")
            if (inspected++ >= 64) return@inspect result("inspection-limit")
            try {
                val proposition = ContentId(pid)
                val bytes = readback.read(proposition)
                val definition = CanonicalCbor.decodeMapBounded(bytes, MAX_ARTIFACT_BYTES, 16, 4096)
                if (definition["kind"] != "kg-source-proposition/1") return@inspect result("not-retained-kg")
                val source = ContentId(definition["sourceContext"] as? String ?: error("Source context missing"))
                val mapped = sources.getOrPut(source) {
                    val text = readback.read(source).decodeToString(throwOnInvalidSequence = true)
                    require(text.length <= MAX_SOURCE_CHARS)
                    val statements = KgNalBridge.bridge(text)
                    require(statements.size <= MAX_DEFINITIONS)
                    statements.associateBy { it.propositionCid(source.value) }
                }[proposition] ?: error("Proposition is not in its retained source")
                require(mapped.propositionBytes(source.value).contentEquals(bytes))
                val expected = mapped.signal(source.value)
                require(signal.angular == expected.angular && signal.subjectCid == expected.subjectCid &&
                    signal.objectCid == expected.objectCid && signal.relation == expected.relation && signal.temporal == expected.temporal)
                result("verified-retained-kg", source.value)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { result(if (readback.limited) "inspection-limit" else "unavailable-or-unverified") }
        }
    }

    /** A flat, bounded request; null or malformed fields never become an accidental wildcard. */
    fun roleRequest(value: Any?): Map<String, Any?> {
        val raw = if (value is String) {
            require(value.length <= 16_384 && value.encodeToByteArray(throwOnInvalidSequence = true).size <= 16_384) { "Exact role request exceeds 16KiB" }
            var depth = 0; var quoted = false; var escaped = false
            for (ch in value) if (quoted) {
                if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false
            } else when (ch) {
                '"' -> quoted = true
                '{', '[' -> require(++depth <= 1) { "Exact role request must be flat" }
                '}', ']' -> require(--depth >= 0) { "Malformed exact role request" }
            }
            require(depth == 0 && !quoted) { "Malformed exact role request" }
            reifyStrict(value)
        } else value
        require(raw is Map<*, *> && raw.size in 3..4 && raw.keys.all { it in _s["sourceContext", "predicate", "subject", "object"] }) { "Exact role request requires sourceContext, predicate, subject and optional object" }
        val keys = HashSet<Any?>()
        require(raw.entries.all { keys.add(it.key) }) { "Duplicate exact role request field" }
        ContentId(requireNotNull(raw["sourceContext"] as? String) { "sourceContext CID required" })
        for (name in s_["predicate", "subject", "object"].view) {
            if (name == "object" && name !in raw) continue
            val token = requireNotNull(raw[name] as? String) { "$name must be an exact lexical token" }
            require(token.isNotBlank() && token.encodeToByteArray(throwOnInvalidSequence = true).size <= 4096) { "$name token exceeds profile" }
        }
        return raw.entries.associate { (it.key as String j it.value).pair }
    }

    /**
     * Read-only lexical role selection in ONE complete retained source. The local quads reify
     * documentary propositions; they never enter the global store, bag, review or rule engine.
     */
    fun selectRoles(cas: CasStore?, request: Map<String, Any?>,
        resident: (Long) -> SemanticSignal?): Map<String, Any?> {
        val checked = roleRequest(request)
        val context = ContentId(checked["sourceContext"] as String)
        fun result(status: String, complete: Boolean = false, extra: Map<String, Any?> = emptyMap()) = _m[
            "kind" j "kg-exact-role-selection/1", "profile" j "retained-ground-binary-kif/1", "status" j status,
            "complete" j complete, "sourceContext" j context.value, "request" j checked,
            "scope" j "source-local lexical role selection, not inference or legal truth",
            "termIdentity" j "exact-source-local-lexical-tokens", "persistence" j "request-local",
            "sourceAuthentication" j "not-established", "reviewAuthority" j "not-established", "currentness" j "not-checked",
            "admission" j "not-performed", "attention" j "not-performed", "matches" j emptyList<Any?>()] + extra
        val readback = Readback(cas)
        val text = try { readback.read(context).decodeToString(throwOnInvalidSequence = true) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return result(if (readback.limited) "BOUNDED" else "UNVERIFIED_SOURCE") }
        if (text.length > MAX_SOURCE_CHARS) return result("BOUNDED")
        val statements = try {
            require(KgNalBridge.sniff(text) == KgFormat.KIF)
            KgNalBridge.bridge(text)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return result("UNSUPPORTED_SOURCE_PROFILE") }
        if (statements.size > MAX_DEFINITIONS) return result("BOUNDED")
        val definitions = statements.associateBy { it.propositionCid(context.value) }
        if (definitions.size > MAX_ROLE_DEFINITIONS) return result("BOUNDED")
        try {
            // Verify ALL definitions, including unrelated ones, before producing any query rows.
            for ((pid, mapped) in definitions) require(readback.read(pid).contentEquals(mapped.propositionBytes(context.value)))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return result(if (readback.limited) "BOUNDED" else "UNVERIFIED_SOURCE") }
        val graph = ROLE_VOCAB + "graph:" + context.hex
        val store = QuadStore()
        fun iri(value: ContentId) = RdfTerm.Iri("urn:sha256:${value.hex}")
        fun literal(value: String): String {
            val term = RdfTerm.Literal(value)
            val encoded = buildString { for (ch in term.toTurtle()) if (ch.code < 32) append("\\u" + ch.code.toString(16).padStart(4, '0')) else append(ch) }
            require(TurtleRdf.parseTerm(encoded) == term) { "Role literal cannot preserve source text" }
            return encoded
        }
        val query: String
        val response: Map<*, *>
        try {
            fun emit(pid: ContentId, predicate: String, term: String) = store.add(iri(pid).toTurtle(), "<$ROLE_VOCAB$predicate>", term, "<$graph>")
            for ((pid, mapped) in definitions) {
                emit(pid, "sourceContext", iri(context).toTurtle())
                emit(pid, "predicateToken", literal(mapped.triplet.predicate))
                emit(pid, "subjectToken", literal(mapped.triplet.subject))
                emit(pid, "objectToken", literal(mapped.triplet.obj))
            }
            val exactObject = checked["object"]?.let { " ?proposition <${ROLE_VOCAB}objectToken> ${literal(it as String)} ." }.orEmpty()
            query = "SELECT ?proposition ?object WHERE { GRAPH <$graph> { " +
                "?proposition <${ROLE_VOCAB}sourceContext> ${iri(context).toTurtle()} . " +
                "?proposition <${ROLE_VOCAB}predicateToken> ${literal(checked["predicate"] as String)} . " +
                "?proposition <${ROLE_VOCAB}subjectToken> ${literal(checked["subject"] as String)} . " +
                "?proposition <${ROLE_VOCAB}objectToken> ?object .$exactObject } }"
            val reply = SparqlSurface(store).answer(query, emptyList(), emptyList(), "json", "", selected = SparqlSource.STORE)
            require(reply.status == 200 && reply.body.encodeToByteArray().size <= 1024 * 1024)
            response = requireNotNull(reifyStrict(reply.body) as? Map<*, *>)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return result("QUERY_REFUSED") }
        val matches = try {
            val rows = ((response["results"] as Map<*, *>)["bindings"] as List<*>)
            require(rows.size <= MAX_ROLE_DEFINITIONS)
            val seen = HashSet<ContentId>()
            rows.map { raw ->
                val row = raw as Map<*, *>
                val p = row["proposition"] as Map<*, *>; val o = row["object"] as Map<*, *>
                require(p["type"] == "uri" && o["type"] == "literal" && o.keys == _s["type", "value"])
                val pid = ContentId("sha256:" + (p["value"] as String).removePrefix("urn:sha256:"))
                require(p["value"] == iri(pid).iri && seen.add(pid))
                val mapped = definitions.getValue(pid); val t = mapped.triplet
                require(t.predicate == checked["predicate"] && t.subject == checked["subject"] && t.obj == o["value"] &&
                    ("object" !in checked || t.obj == checked["object"]))
                val expected = mapped.signal(context.value); val current = resident(expected.angular)
                val status = when {
                    current == null -> "MATCH_NOT_RESIDENT"
                    current.propositionCid == null -> "RESIDENT_PID_UNRESOLVED"
                    current.propositionCid != pid.value -> "RESIDENT_PID_MISMATCH"
                    current.angular != expected.angular || current.subjectCid != expected.subjectCid || current.objectCid != expected.objectCid ||
                        current.relation != expected.relation || current.temporal != expected.temporal -> "RESIDENT_STRUCTURE_MISMATCH"
                    else -> "MATCH_RESIDENT"
                }
                _m["propositionCid" j pid.value, "angular" j expected.angular.toString(), "sourceContext" j context.value,
                    "predicate" j t.predicate, "subject" j t.subject, "object" j t.obj,
                    "subjectCid" j t.subjectCid, "objectCid" j t.objectCid, "residentStatus" j status]
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return result("QUERY_REFUSED") }
        return result(if (matches.isEmpty()) "NO_MATCH_IN_COMPLETE_SOURCE" else "MATCHES_IN_COMPLETE_SOURCE", true, _m[
            "source" j _m["sourceContext" j context.value, "begin" j 0, "end" j text.length, "quote" j text,
                "offsetUnit" j "utf16-code-units", "endExclusive" j true, "meaning" j "whole accepted manual KG source"],
            "definitions" j definitions.size, "graph" j graph, "quads" j store.size, "query" j query, "results" j response, "matches" j matches])
    }

    /** Called only by the bag's existing single writer, before its retention acknowledgement. */
    fun retain(cas: CasStore?, batch: Batch): StorageDurability {
        val store = cas ?: throw KgRetentionRefused(KgRetentionIssue.MISSING_CAS)
        val reader = store as? BoundedCasReader ?: throw KgRetentionRefused(KgRetentionIssue.BOUNDED_READER_UNAVAILABLE)
        if (batch.definitions.size > MAX_DEFINITIONS || batch.source.size > MAX_ARTIFACT_BYTES)
            throw KgRetentionRefused(KgRetentionIssue.LIMIT)
        var total = 0L
        val ids = HashSet<ContentId>()
        // Check the entire batch before the first write, including publicly constructed batches.
        for (artifact in batch.artifacts.view) {
            total += artifact.size
            if (artifact.size > MAX_ARTIFACT_BYTES || total > MAX_TOTAL_BYTES)
                throw KgRetentionRefused(KgRetentionIssue.LIMIT)
            if (!ids.add(artifact.cid) || ContentId.of(artifact.bytes()) != artifact.cid)
                throw KgRetentionRefused(KgRetentionIssue.IDENTITY)
        }
        for (artifact in batch.artifacts.view) step(KgRetentionIssue.PUT) {
            check(store.put(artifact.bytes()) == artifact.cid) { "CAS acknowledgement differs from payload CID" }
        }
        step(KgRetentionIssue.SYNC) { store.sync() }
        for (artifact in batch.artifacts.view) step(KgRetentionIssue.READBACK) {
            val readback = checkNotNull(reader.getBounded(artifact.cid, artifact.size)) { "Retained KG artifact unavailable" }
            check(readback.size == artifact.size && ContentId.of(readback) == artifact.cid &&
                readback.contentEquals(artifact.bytes())) { "Retained KG artifact differs from its expected bytes" }
        }
        return store.durability
    }

    private inline fun step(issue: KgRetentionIssue, action: () -> Unit) {
        try { action() } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { throw KgRetentionRefused(issue, failure) }
    }
}
