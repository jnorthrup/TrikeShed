package borg.trikeshed.lcnc

import borg.trikeshed.collections.associative.Cbor
import borg.trikeshed.collections.associative.Item
import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.job.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.ValueBudget
import borg.trikeshed.parse.confix.*

/** New case blobs preserve fractions on runtimes where Int and Double share one number representation. */
fun encodeLcncCase(fields: Map<String, Any?>): ByteArray {
    fun item(value: Any?): Item = when (value) {
        null -> Item.Nil
        is String -> Item.Str(value)
        is Boolean -> Item.Bool(value)
        is Long -> Item.Num(value)
        is Number -> Item.Flt(value.toDouble().also { require(it.isFinite()) { "non-finite case number" } })
        is Map<*, *> -> {
            require(value.keys.all { it is String }) { "case object keys must be strings" }
            val keys = value.keys.map { it as String }.sorted().toSeries()
            Item.Map(keys.size j { index: Int ->
                val key = keys[index]
                key j item(value[key])
            })
        }
        is List<*> -> Item.Arr(value.size j { item(value[it]) })
        else -> throw IllegalArgumentException("unsupported case value")
    }
    return Cbor.encode(item(fields))
}

enum class LcncCaseFailure { NOT_FOUND, CAPACITY, CONFLICT, INVALID, UNSUPPORTED }

class LcncCaseException(val failure: LcncCaseFailure, message: String) : IllegalStateException(message)

sealed interface LcncCaseRequest {
    val caseId: String

    data class Initialize(override val caseId: String, val idempotencyKey: String) : LcncCaseRequest
    data class Read(override val caseId: String) : LcncCaseRequest
    data class Transition(
        override val caseId: String,
        val idempotencyKey: String,
        val expectedRevision: Long,
        val priorStateCid: ContentId,
        val requestedTarget: String,
    ) : LcncCaseRequest
}

val LcncCaseRequest.intent: String get() = when (this) {
    is LcncCaseRequest.Initialize -> "initialize"
    is LcncCaseRequest.Read -> "read"
    is LcncCaseRequest.Transition -> "transition"
}

/** The original numeric token is checked before JSON maps can round it. */
fun lcncCaseRequest(doc: ConfixDoc): LcncCaseRequest? = try { caseRequest(doc) }
catch (failure: LcncCaseException) { throw failure }
catch (failure: IllegalArgumentException) {
    throw LcncCaseException(LcncCaseFailure.INVALID, failure.message ?: "invalid case request")
}

private fun caseRequest(doc: ConfixDoc): LcncCaseRequest? {
    fun objectFields(cell: ConfixCell): Map<String, ConfixCell> {
        require(cell.row.tag == IOMemento.IoObject) { "case request must be an object" }
        val children = cell.cellKids
        require(children.size % 2 == 0) { "invalid case object fields" }
        val fields = linkedMapOf<String, ConfixCell>()
        for (index in 0 until children.size / 2) {
            val name = children[index * 2]
            require(name.row.tag == IOMemento.IoString) { "invalid case request field name" }
            val key = name.reify() as? String ?: throw IllegalArgumentException("invalid case request field name")
            require(!fields.containsKey(key)) { "duplicate case request field" }
            fields[key] = children[index * 2 + 1]
        }
        return fields
    }
    val inputs = doc.docAt("inputs")?.takeIf { it.row.tag == IOMemento.IoObject }?.let(::objectFields)
    require(inputs?.keys?.none { it.startsWith(LcncCaseProgram.BINDING_PREFIX) } != false) {
        "reserved case input"
    }
    val selected = doc.docAt("case") ?: return null
    val root = objectFields(requireNotNull(doc.rootCell))
    val fields = objectFields(selected)
    require(s_["pulse", "baseReceiptCid", "sourceReceiptCid"].view.none { root.containsKey(it) } &&
        inputs?.containsKey("evidence") != true) { "case intent cannot combine execution evidence protocols" }
    fun string(name: String): String {
        val cell = fields[name]
        return (cell?.takeIf { it.row.tag == IOMemento.IoString }?.reify() as? String)?.takeIf { it.isNotBlank() }
            ?: throw LcncCaseException(LcncCaseFailure.INVALID, "case $name must be a nonblank string")
    }
    val caseId = string("caseId")
    val base = setOf("intent", "caseId")
    return when (val intent = string("intent")) {
        "initialize" -> {
            require(fields.keys == base + "idempotencyKey") { "invalid initialize fields" }
            LcncCaseRequest.Initialize(caseId, string("idempotencyKey"))
        }
        "read" -> {
            require(fields.keys == base) { "invalid read fields" }
            LcncCaseRequest.Read(caseId)
        }
        "transition" -> {
            require(fields.keys == base + setOf("idempotencyKey", "expectedRevision", "priorStateCid", "requestedTarget")) {
                "invalid transition fields"
            }
            val revision = requireNotNull(fields["expectedRevision"])
            require(revision.row.tag == IOMemento.IoInt || revision.row.tag == IOMemento.IoLong ||
                revision.row.tag == IOMemento.IoDouble) { "case expectedRevision must be numeric" }
            val literal = ByteArray(revision.row.close - revision.row.open + 1) {
                revision.src[revision.row.open + it]
            }.decodeToString()
            val expected = requireNotNull(stateRevision(literal)) { "case expectedRevision must be an exact nonnegative Long" }
            LcncCaseRequest.Transition(caseId, string("idempotencyKey"), expected,
                ContentId(string("priorStateCid")), string("requestedTarget"))
        }
        else -> throw LcncCaseException(LcncCaseFailure.INVALID, "unknown case intent: $intent")
    }
}

/** Exact queued intent; JSON receipts carry its CID, not a second rounded revision. */
data class LcncCaseFrame(
    val request: LcncCaseRequest,
    val programCid: ContentId,
    val inputs: Map<String, Any?>,
    val cid: ContentId,
) {
    fun fields(): Map<String, Any?> = fields(request, programCid, inputs)

    companion object {
        fun fields(request: LcncCaseRequest, programCid: ContentId, inputs: Map<String, Any?>): Map<String, Any?> =
            mapOf("kind" to "lcnc-case-request", "version" to 1L, "intent" to request.intent,
                "caseId" to request.caseId, "programCid" to programCid.value) + when (request) {
                is LcncCaseRequest.Initialize -> mapOf("idempotencyKey" to request.idempotencyKey, "inputs" to inputs)
                is LcncCaseRequest.Read -> emptyMap()
                is LcncCaseRequest.Transition -> mapOf("idempotencyKey" to request.idempotencyKey,
                    "expectedRevision" to request.expectedRevision, "priorStateCid" to request.priorStateCid.value,
                    "requestedTarget" to request.requestedTarget)
            }

        fun retain(cas: CasStore, request: LcncCaseRequest, programCid: ContentId,
                   inputs: Map<String, Any?>): LcncCaseFrame {
            validate(request, inputs)
            val bytes = encodeLcncCase(fields(request, programCid, inputs))
            val cid = ContentId.of(bytes)
            check(cas.put(bytes) == cid) { "case request CAS acknowledgement mismatch: $cid" }
            cas.verifyState(cid.value, synchronize = true)
            return load(cas, cid)
        }

        fun load(cas: CasStore, cid: ContentId): LcncCaseFrame {
            cas.verifyState(cid.value)
            val bytes = checkNotNull(cas.get(cid)) { "missing case request: $cid" }
            check(ContentId.of(bytes) == cid) { "case request digest mismatch: $cid" }
            val fields = CanonicalCbor.decodeMap(bytes)
            fun string(name: String): String = (fields[name] as? String)?.takeIf { it.isNotBlank() }
                ?: error("invalid case request $name: $cid")
            check(fields["kind"] == "lcnc-case-request" && fields["version"] == 1L) { "invalid case request format: $cid" }
            val program = ContentId(string("programCid"))
            val caseId = string("caseId")
            val request = when (string("intent")) {
                "initialize" -> LcncCaseRequest.Initialize(caseId, string("idempotencyKey"))
                "read" -> LcncCaseRequest.Read(caseId)
                "transition" -> LcncCaseRequest.Transition(caseId, string("idempotencyKey"),
                    (fields["expectedRevision"] as? Long)?.takeIf { it >= 0L } ?: error("invalid case revision: $cid"),
                    ContentId(string("priorStateCid")), string("requestedTarget"))
                else -> error("invalid case request intent: $cid")
            }
            val inputs = if (request is LcncCaseRequest.Initialize) {
                val values = fields["inputs"] as? Map<*, *> ?: error("invalid case inputs: $cid")
                require(values.keys.all { it is String }) { "invalid case input names: $cid" }
                @Suppress("UNCHECKED_CAST")
                (values as Map<String, Any?>).toMap()
            } else emptyMap()
            val canonical = encodeLcncCase(LcncCaseFrame.fields(request, program, inputs))
            check(canonical.contentEquals(bytes)) { "case request must retain canonical typed fields: $cid" }
            validate(request, inputs)
            return LcncCaseFrame(request, program, inputs, cid)
        }

        private fun validate(request: LcncCaseRequest, inputs: Map<String, Any?>) {
            require(request.caseId.isNotBlank()) { "case id must not be blank" }
            when (request) {
                is LcncCaseRequest.Initialize -> require(request.idempotencyKey.isNotBlank()) { "case key must not be blank" }
                is LcncCaseRequest.Read -> Unit
                is LcncCaseRequest.Transition -> require(request.idempotencyKey.isNotBlank() &&
                    request.expectedRevision >= 0L && request.requestedTarget.isNotBlank()) { "invalid case transition" }
            }
            require(inputs.keys.none { it.startsWith(LcncCaseProgram.BINDING_PREFIX) } && !inputs.containsKey("evidence")) {
                "reserved case input"
            }
            if (request is LcncCaseRequest.Initialize)
                require((inputs["brief"] as? String)?.isNotBlank() == true) { "case initialization requires brief" }
            else require(inputs.isEmpty()) { "case inputs belong to initialization" }
            require(ValueBudget().violation(inputs) == null) { "invalid case input payload" }
            fun json(value: Any?): Boolean = when (value) {
                null, is String, is Boolean, is Byte, is Short, is Int, is Long -> true
                is Float -> value.isFinite()
                is Double -> value.isFinite()
                is Map<*, *> -> value.keys.all { it is String } && value.values.all(::json)
                is List<*> -> value.all(::json)
                else -> false
            }
            require(json(inputs)) { "case inputs must contain finite JSON values" }
        }
    }
}
