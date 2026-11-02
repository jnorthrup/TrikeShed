package borg.trikeshed.rdf

/** Physical query inputs, never a reviewed/entailed dataset or per-binding provenance. */
enum class SparqlSource(val id: String, val stored: Boolean, val projected: Boolean) {
    STORE("quad-store", true, false), RETE("rete-projection", false, true), UNION("quad-store+rete", true, true);

    fun descriptor(liveAvailable: Boolean): Map<String, Any?> = mapOf(
        "selected" to id,
        "sources" to buildList {
            if (stored) add("quad-store")
            if (projected && liveAvailable) add("rete-projection")
        },
        "scope" to "raw-matched-data",
        "reviewAuthority" to "not-established",
        "bindingProvenance" to "not-established",
        "sampling" to if (stored && projected && liveAvailable) "separate-store-and-live-snapshots" else "single-source",
    )

    fun headers(liveAvailable: Boolean): Map<String, String> = mapOf(
        "X-TrikeShed-Source" to id,
        "X-TrikeShed-Inputs" to (descriptor(liveAvailable)["sources"] as List<*>).joinToString(","),
        "X-TrikeShed-Scope" to "raw-matched-data",
        "X-TrikeShed-Review-Authority" to "not-established",
        "X-TrikeShed-Binding-Provenance" to "not-established",
        "X-TrikeShed-Sampling" to descriptor(liveAvailable).getValue("sampling").toString(),
    )

    companion object {
        fun of(id: String): SparqlSource = entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown SPARQL source")
    }
}
