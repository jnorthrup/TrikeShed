package borg.trikeshed.web.harness

import borg.trikeshed.graal.console.Projection

/** Graal's byte projections shared by terrain, sheets, results and immutable refs (graal-file-viewer.js). */
object FileViewerRules {
    const val LIMIT = 1500000

    fun isCid(value: String?): Boolean = isArchiveCid(value)

    fun chip(cid: String?, label: String = "blob"): String {
        if (!isCid(cid)) return Projection.escAll(cid ?: "")
        return "<button class=\"blob-ref\" data-blob-cid=\"" + cid + "\" title=\"Open blob " + cid + "\">" + Projection.escAll(label) + " " + cid!!.substring(7, 19) + "</button>"
    }

    private val IMAGE_EXT = Regex("^(svg|png|jpg|jpeg|gif|webp|avif)$")
    private val HTML_EXT = Regex("^(html|htm)$")
    private val TEXT_TYPE = Regex("json|xml|javascript")
    private val TEXT_EXT = Regex("^(kt|kts|java|py|js|ts|json|yaml|yml|css|sh|gradle|xml|txt|toml|properties|sql|rs|c|h|cpp|go|jsonl)$")
    private val CONTROL = Regex("[\\x00-\\x08\\x0e-\\x1f]")

    /** Projection mode by media type, extension and magic; [decodedText] is the fatal UTF-8 decode, null when it failed. */
    fun kind(head: IntArray, type: String, id: String, decodedText: () -> String?): String {
        val ext = Projection.extOf(id); val ct = type.lowercase()
        if (ct.contains("java-vm") || ext == "class" || (head.size >= 4 && head[0] == 202 && head[1] == 254 && head[2] == 186 && head[3] == 190)) return "class"
        if (ct.startsWith("image/") || IMAGE_EXT.matches(ext)) return "image"
        if (ext == "md" || ct.contains("markdown")) return "markdown"
        if (ct.contains("html") || HTML_EXT.matches(ext)) return "html"
        if (ct.startsWith("text/") || TEXT_TYPE.containsMatchIn(ct) || TEXT_EXT.matches(ext)) return "text"
        val text = decodedText()
        if (text != null && !CONTROL.containsMatchIn(text)) return "text"
        return "binary"
    }

    fun imageMime(type: String, id: String): String {
        val ext = Projection.extOf(id)
        return if (type.startsWith("image/")) type else if (ext == "svg") "image/svg+xml" else "image/" + (if (ext == "jpg") "jpeg" else ext)
    }

    const val HTML_CSP = "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src &#39;none&#39;; img-src data: blob:; style-src &#39;unsafe-inline&#39;\">"

    fun projectionTable(rows: List<Pair<String, String?>>): String =
        "<table>" + rows.joinToString("") { (k, v) -> "<tr><td>" + Projection.escAll(k) + "</td><td>" + Projection.escAll(v ?: "") + "</td></tr>" } + "</table>"

    fun fieldRows(fields: List<Pair<String, String>>): String =
        "<tr><th>field</th><th>descriptor</th></tr>" + fields.joinToString("") { (n, d) -> "<tr><td>" + Projection.escAll(n) + "</td><td>" + Projection.escAll(d) + "</td></tr>" }

    fun methodRows(methods: List<Triple<String, String, String>>): String =
        "<tr><th>method</th><th>descriptor</th><th>instructions</th></tr>" + methods.joinToString("") { (n, d, i) ->
            "<tr><td>" + Projection.escAll(n) + "</td><td>" + Projection.escAll(d) + "</td><td>" + Projection.escAll(i) + "</td></tr>"
        }

    fun classHeading(name: String): String = "<h3>" + Projection.escAll(name) + "</h3>"

    fun tristate(value: Any?, yes: String, no: String): String = when (value) { true -> yes; false -> no; else -> "unknown" }

    fun mateTitle(className: String, exactRuntimeBlob: Boolean, onClasspath: Boolean): String =
        className + " / " + (if (exactRuntimeBlob) "exact runtime mate" else if (onClasspath) "classpath bytes differ" else "not on runtime classpath")

    const val TEXT_LINES = 1200
    private val JVM_SOURCE = Regex("\\.(kt|kts|java)$", RegexOption.IGNORE_CASE)
    fun jvmSource(id: String): Boolean = JVM_SOURCE.containsMatchIn(id)
}
