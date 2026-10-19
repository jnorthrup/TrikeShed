package borg.trikeshed.job

import borg.trikeshed.cursor.IOMemento
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*

/** Verify the actual referenced blob, including a fresh read after an optional durability barrier. */
fun CasStore.verifyState(stateCid: String, synchronize: Boolean = false) {
    val cid = ContentId(stateCid)
    fun bytes(): ByteArray {
        val value = checkNotNull(get(cid)) { "Missing job state bytes: ${cid.value}" }
        check(ContentId.of(value) == cid) { "Corrupt job state bytes: ${cid.value}" }
        return value
    }
    val before = bytes()
    if (synchronize) {
        sync()
        check(bytes().contentEquals(before)) { "Job state changed after sync: ${cid.value}" }
    }
}

/** State raw entry keeps exact fields instead of the legacy command coercions. */
fun JobFrame.stateCommand(): JobCommand.State {
    require(doc.value("operation") == "state") { "Expected state operation" }
    fun string(name: String): String = requireNotNull(doc.value(name) as? String) {
        "State $name must be a string"
    }.also { require(it.isNotBlank()) { "State $name must not be blank" } }
    val job = JobId.of(string("jobId"))
    val key = string("idempotencyKey")
    val next = string("stateCid").also { ContentId(it) }
    require(doc.docAt("priorStateCid") != null) { "State priorStateCid is required" }
    val prior = doc.value("priorStateCid")?.let {
        require(it is String) { "State priorStateCid must be a string or null" }
        it.also { cid -> ContentId(cid) }
    }
    val revision = requireNotNull(doc.docAt("expectedRevision")) { "State expectedRevision is required" }
    require(revision.row.tag == IOMemento.IoInt || revision.row.tag == IOMemento.IoLong ||
        revision.row.tag == IOMemento.IoDouble) {
        "State expectedRevision must be an exact nonnegative integer"
    }
    val root = requireNotNull(doc.rootCell) { "State command must be an object" }
    val cbor = (root.src[root.row.open].toInt() and 0xe0) == 0xa0
    val expected = if (cbor) {
        when (val number = doc.value("expectedRevision")) {
            is Int -> number.toLong()
            is Long -> number
            is Double -> if (number.isFinite() && number >= 0.0 && number < 9223372036854775808.0 &&
                number % 1.0 == 0.0) number.toLong() else null
            else -> null
        }
    } else {
        val literal = ByteArray(revision.row.close - revision.row.open + 1) {
            revision.src[revision.row.open + it]
        }.decodeToString()
        stateRevision(literal)
    }
    require(expected != null && expected >= 0L) { "State expectedRevision must be an exact nonnegative Long" }
    return JobCommand.State(job, key, expected, prior, next)
}

/** Decimal source is checked before reification can round an out-of-range or fractional value. */
fun stateRevision(literal: String): Long? {
    val match = Regex("(-?)(0|[1-9][0-9]*)(?:\\.([0-9]+))?(?:[eE]([+-]?[0-9]+))?").matchEntire(literal) ?: return null
    val fraction = match.groupValues[3]
    val digits = (match.groupValues[2] + fraction).trimStart('0')
    if (digits.isEmpty()) return 0L // Exact negative zero has the same numeric value as CBOR -0.0.
    if (match.groupValues[1] == "-") return null
    val exponent = match.groupValues[4].ifEmpty { "0" }.toLongOrNull() ?: return null
    if (exponent < -digits.length.toLong() || exponent > 19L + fraction.length) return null
    val shift = exponent - fraction.length
    return if (shift >= 0L) {
        if (digits.length + shift > 19L) null else (digits + "0".repeat(shift.toInt())).toLongOrNull()
    } else {
        val cut = -shift
        if (cut >= digits.length || digits.takeLast(cut.toInt()).any { it != '0' }) null
        else digits.dropLast(cut.toInt()).toLongOrNull()
    }
}
