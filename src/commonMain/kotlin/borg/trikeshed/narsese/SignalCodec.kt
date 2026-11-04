package borg.trikeshed.narsese

/**
 * Canonical byte codec for [SemanticSignal] — the CAS-spill and WAL-payload
 * form. Deterministic length-delimited text (same discipline as
 * DerivationReceipt's canonical serialization): encode(x) is byte-stable, so
 * ContentId.of(encode(x)) is the signal's identity at rest.
 */
object SignalCodec {

    fun encode(s: SemanticSignal): ByteArray = buildString {
        field(s.angular.toString())
        field(s.evidence.packed.toString())
        field(s.relation.name)
        field(s.subjectCid)
        field(s.objectCid ?: "")
        val t = s.temporal
        field(t?.grade?.name ?: "")
        field(t?.validFrom ?: "")
        field(t?.validUntil ?: "")
        field(t?.sourceCid ?: "")
        field(s.provenanceCid ?: "")
        field(s.basisBloom.toString())
        // Null retains the exact historical eleven-field bytes and their CAS identities.
        s.propositionCid?.let { field(it) }
    }.encodeToByteArray(throwOnInvalidSequence = true)

    fun decode(bytes: ByteArray): SemanticSignal {
        val fields = parse(bytes.decodeToString(throwOnInvalidSequence = true))
        // 10 fields = legacy (pre-basisBloom) WAL/CAS records: bloom defaults to 0
        require(fields.size in 10..12) { "SignalCodec: expected 10, 11 or 12 fields, got ${fields.size}" }
        val gradeName = fields[5]
        val temporal = if (gradeName.isEmpty()) null else TemporalSignal(
            grade = TemporalGrade.valueOf(gradeName),
            validFrom = fields[6].ifEmpty { null },
            validUntil = fields[7].ifEmpty { null },
            sourceCid = fields[8].ifEmpty { null },
        )
        return SemanticSignal(
            angular = fields[0].toLong(),
            evidence = EvidenceCoord(fields[1].toLong()),
            relation = RelationKind.valueOf(fields[2]),
            subjectCid = fields[3],
            objectCid = fields[4].ifEmpty { null },
            temporal = temporal,
            provenanceCid = fields[9].ifEmpty { null },
            basisBloom = if (fields.size >= 11) fields[10].toLong() else 0L,
            propositionCid = if (fields.size == 12) fields[11] else null,
        )
    }

    private fun StringBuilder.field(v: String) {
        append(v.length).append(':').append(v).append(';')
    }

    private fun parse(text: String): List<String> {
        val out = ArrayList<String>(10)
        var i = 0
        while (i < text.length) {
            val colon = text.indexOf(':', i)
            require(colon > i) { "SignalCodec: malformed length prefix at $i" }
            val len = text.substring(i, colon).toInt()
            val start = colon + 1
            out.add(text.substring(start, start + len))
            require(text[start + len] == ';') { "SignalCodec: missing delimiter at ${start + len}" }
            i = start + len + 1
        }
        return out
    }
}
