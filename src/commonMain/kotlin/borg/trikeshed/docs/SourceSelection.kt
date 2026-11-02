package borg.trikeshed.docs

import borg.trikeshed.lib.*
import borg.trikeshed.wiki.WikiSurface

/** A navigation reference. Only retained receipt and current source reads establish its evidence. */
data class SourceSelection(val receiptCid: String, val proposal: Int, val project: String, val document: String) {
    fun query(): String = "sourceReceipt=${WikiSurface.encode(receiptCid)}&sourceProposal=$proposal&sourceProject=${WikiSurface.encode(project)}&sourceDocument=${WikiSurface.encode(document)}"
    fun documentsHref(): String = "/documents?${query()}&passageReceipt=${WikiSurface.encode(receiptCid)}&passageProposal=$proposal#${WikiSurface.encode(project)}/${WikiSurface.encode(document)}"
    fun href(surface: String): String {
        require(surface in _s["wiki", "curator", "panels"])
        return "/$surface?${query()}"
    }
    fun applicationHref(destination: String): String? = when (destination) {
        "documents" -> documentsHref()
        "board" -> href("panels")
        "wiki", "curator", "panels" -> href(destination)
        else -> null
    }
    fun matches(passage: DocumentPassage): Boolean = passage.receiptCid == receiptCid && passage.ordinal == proposal &&
        passage.source.project == project && passage.source.id == document

    fun html(): String = buildString {
        append("<section data-source-context=\"1\"><h2>Selected source context</h2><p>")
        append(DocumentMarkdown.escape(project)).append(" / ").append(DocumentMarkdown.escape(document))
        append(" · proposal ").append(proposal).append("</p><p>This navigation reference does not establish current source bytes or review.</p><nav>")
        append("<a data-source-return=\"1\" href=\"").append(DocumentMarkdown.escape(documentsHref())).append("\">Return to source passage</a>")
        for ((surface, label) in s_["wiki" j "Wiki", "curator" j "Curator", "panels" j "Blackboard"].view)
            append(" · <a href=\"").append(DocumentMarkdown.escape(href(surface))).append("\">").append(label).append("</a>")
        append(" · <a href=\"/documents#curation/").append(WikiSurface.encode(receiptCid)).append("\">Retained curation receipt</a></nav></section>")
    }

    companion object {
        val keys: Series<String> = s_["sourceReceipt", "sourceProposal", "sourceProject", "sourceDocument"]
        fun requested(values: Map<String, List<String>>): Boolean = keys.view.any { it in values }
        /** Percent octets decode as whole strict UTF-8; repeated keys stay visible to admission. */
        fun queryValues(raw: String): Map<String, List<String>> {
            require(raw.length <= 32_768) { "Source context query exceeds limit" }
            fun decode(value: String): String {
                require(value.length <= 4096) { "Source context field exceeds limit" }
                val bytes = mutableListOf<Byte>()
                var at = 0
                while (at < value.length) {
                    val ch = value[at++]
                    if (ch == '%') {
                        require(at + 1 < value.length) { "Incomplete URL octet" }
                        val octet = value.substring(at, at + 2).toIntOrNull(16)
                        require(octet != null && value[at].isLetterOrDigit() && value[at + 1].isLetterOrDigit()) { "Invalid URL octet" }
                        bytes.add(octet.toByte()); at += 2
                    } else {
                        require(ch.code < 128) { "URL fields require UTF-8 percent encoding" }
                        bytes.add((if (ch == '+') 32 else ch.code).toByte())
                    }
                }
                return bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
            }
            val values = mutableMapOf<String, MutableList<String>>()
            for (part in raw.removePrefix("?").split('&')) {
                if (part.isEmpty()) continue
                val key = decode(part.substringBefore('='))
                if (key !in keys.view) continue
                values.getOrPut(key) { mutableListOf() }.add(decode(part.substringAfter('=', "")))
            }
            return values
        }
        fun parse(values: Map<String, List<String>>): SourceSelection {
            fun one(key: String): String {
                val all = requireNotNull(values[key]) { "Incomplete source context" }
                require(all.size == 1) { "Repeated source context field" }
                val value = all.single()
                require(value.length in 1..1024 && value.none { it < ' ' || it == '\u007f' } &&
                    value.encodeToByteArray().decodeToString(throwOnInvalidSequence = true) == value) { "Invalid source context field" }
                return value
            }
            val receipt = one("sourceReceipt")
            require(Regex("^sha256:[0-9a-f]{64}$").matches(receipt)) { "Invalid retained receipt CID" }
            val ordinal = one("sourceProposal")
            require(Regex("^(0|[1-9][0-9]{0,8})$").matches(ordinal)) { "Invalid proposal ordinal" }
            val project = one("sourceProject")
            val document = one("sourceDocument")
            require(project != DocumentSurface.CURATION_HASH) { "Reserved project identity" }
            return SourceSelection(receipt, ordinal.toInt(), project, document)
        }
        fun from(passage: DocumentPassage): SourceSelection = SourceSelection(passage.receiptCid, passage.ordinal,
            passage.source.project, passage.source.id)
    }
}
