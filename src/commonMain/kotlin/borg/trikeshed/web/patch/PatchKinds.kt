package borg.trikeshed.web.patch

/**
 * Port kind lookups of the patch surface. Kinds and their accepted supertypes are served by
 * Kotlin (/api/lcnc/contracts kindAcceptance); this is lookup only, no parallel subtype rule.
 */
object PatchKinds {
    val kindClass: Map<String, String> = mapOf("trigger" to "t-trig", "json" to "t-json", "text" to "t-text", "id" to "t-id", "num" to "t-num")
    val kindColor: Map<String, String> = mapOf("t-text" to "#6ea8fe", "t-json" to "#3fd0ff", "t-id" to "#b07aff", "t-num" to "#ffb02e", "t-trig" to "#3ddc84")

    /** "*" is the explicit generic ring escape, not a semantic class */
    fun compatible(a: String?, b: String?, acceptance: Map<String, List<String>>): Boolean =
        !a.isNullOrEmpty() && !b.isNullOrEmpty() && (a == "*" || b == "*" || acceptance[a]?.contains(b) == true)

    fun portClass(kind: String?, acceptance: Map<String, List<String>>): String {
        kindClass[kind]?.let { return it }
        if (acceptance[kind]?.contains("json") == true) return kindClass.getValue("json")
        return "t-any"
    }

    /** JS String.replace with a string pattern: first occurrence only */
    fun bare(port: String): String = port.replaceFirst("?", "")

    fun cableKey(fromNode: String, fromPort: String, toNode: String, toPort: String): String =
        fromNode + "." + bare(fromPort) + ">" + toNode + "." + bare(toPort)

    /** ring containment: data flows lateral or inward — the source scope path prefixes the sink's */
    fun reaches(source: List<String>, sink: List<String>): Boolean =
        source.size <= sink.size && source.indices.all { sink[it] == source[it] }
}

object PatchHtml {
    fun text(s: String): String = buildString { for (c in s) append(when (c) { '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; else -> c.toString() }) }
    fun attr(s: String): String = buildString { for (c in s) append(when (c) { '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; else -> c.toString() }) }
}
