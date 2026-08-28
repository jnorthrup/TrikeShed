package borg.trikeshed.pdf

import borg.trikeshed.lib.Series

/**
 * Baremetal PDF object model — the seven COS types plus streams and indirect
 * references. No external parser, no Tika, no PDFBox: a PDF is `%PDF-1.x`, a
 * soup of `N G obj … endobj` bodies, cross-reference bookkeeping we do NOT
 * trust (a scan finds every object even when the xref is torn), and a trailer
 * whose only job is naming the catalog — which we find by `/Type /Catalog`
 * anyway.
 */
sealed class PdfObject {
    data class PNum(val value: Double) : PdfObject() {
        val int: Int get() = value.toInt()
        val long: Long get() = value.toLong()
    }

    data class PBool(val value: Boolean) : PdfObject()
    data object PNull : PdfObject()

    /** `/Name` — `#xx` hex escapes already resolved. */
    data class PName(val value: String) : PdfObject()

    /** `(literal)` or `<hex>` string — raw BYTES; font encoding decides meaning later. */
    class PStr(val bytes: ByteArray) : PdfObject() {
        val latin1: String get() = bytes.map { (it.toInt() and 0xFF).toChar() }.toCharArray().concatToString()
    }

    data class PArr(val items: List<PdfObject>) : PdfObject()

    data class PDict(val entries: Map<String, PdfObject>) : PdfObject() {
        operator fun get(key: String): PdfObject? = entries[key]
    }

    /** `N G R` — resolved through [PdfDocument.resolve]. */
    data class PRef(val num: Int, val gen: Int) : PdfObject()

    /**
     * Dict + raw (still-encoded) stream bytes as an IMMUTABLE slice view over the
     * source (mapped file included) — nothing is copied until a filter must
     * materialize. [decoded] is filled by the filter pass; image codecs never are.
     */
    class PStream(val dict: PDict, val raw: Series<Byte>) : PdfObject() {
        var decoded: ByteArray? = null
        var decodeNote: String = ""
    }
}

data class ObjId(val num: Int, val gen: Int)

/**
 * The disassembled document: every body object by id, the catalog when found,
 * and the structural census the graal projection can render.
 */
class PdfDocument(
    val version: String,
    val objects: Map<ObjId, PdfObject>,
    val catalog: PdfObject.PDict?,
    /** Object-stream expansion count, undecodable-filter notes, etc. */
    val notes: List<String>,
) {
    fun resolve(o: PdfObject?): PdfObject? = when (o) {
        is PdfObject.PRef -> {
            // generation numbers lie in torn files; prefer exact, fall back to any gen
            objects[ObjId(o.num, o.gen)] ?: objects.entries.firstOrNull { it.key.num == o.num }?.value
        }
        else -> o
    }

    fun dict(o: PdfObject?): PdfObject.PDict? = when (val r = resolve(o)) {
        is PdfObject.PDict -> r
        is PdfObject.PStream -> r.dict
        else -> null
    }

    /** Structural census — the disassembler view. */
    fun census(): Map<String, Any?> {
        var streams = 0
        val filters = HashMap<String, Int>()
        val types = HashMap<String, Int>()
        for (o in objects.values) {
            val d = when (o) {
                is PdfObject.PStream -> { streams++; o.dict }
                is PdfObject.PDict -> o
                else -> null
            }
            (d?.get("Type") as? PdfObject.PName)?.let { types[it.value] = (types[it.value] ?: 0) + 1 }
            if (o is PdfObject.PStream) {
                when (val f = d?.get("Filter")) {
                    is PdfObject.PName -> filters[f.value] = (filters[f.value] ?: 0) + 1
                    is PdfObject.PArr -> {
                        // Bolt: avoid intermediate List allocations from filterIsInstance and forEach by using for loop with type check
                        for (item in f.items) {
                            if (item is PdfObject.PName) {
                                filters[item.value] = (filters[item.value] ?: 0) + 1
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
        return mapOf(
            "version" to version,
            "objects" to objects.size,
            "streams" to streams,
            "filters" to filters,
            "types" to types,
            "catalog" to (catalog != null),
            "notes" to notes,
        )
    }
}
