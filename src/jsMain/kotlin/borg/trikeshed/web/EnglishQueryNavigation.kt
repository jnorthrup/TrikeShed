package borg.trikeshed.web

import borg.trikeshed.docs.DocumentSurface
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.channels.consume
import borg.trikeshed.wiki.WikiSurface

/** A bounded navigation reference, never a program, query result, credential or source authority. */
object EnglishQueryNavigation {
    const val PRESET = "preset-english-copular-query"
    const val MODE = "current"
    const val MAX_QUERY = 16_384
    val keys: Series<String> = s_["englishReceipt", "englishProject", "englishDocument", "englishCid",
        "englishRev", "englishSeq", "englishBegin", "englishEnd"]

    data class Selection(val receiptCid: String, val project: String, val document: String,
        val originalCid: String, val revision: String, val sequence: Long, val begin: Int, val end: Int) {
        init {
            ContentId(receiptCid); ContentId(originalCid)
            for (value in s_[project, document, revision].view) text(value)
            require(sequence in 0..MAX_SAFE && begin >= 0 && end > begin && end <= 4 * 1024 * 1024 && end - begin <= 16_384) {
                "Invalid English source revision or range"
            }
        }
        fun request(): Map<String, Any?> = _m["receiptCid" j receiptCid, "project" j project,
            "id" j document, "cid" j originalCid, "rev" j revision, "seq" j sequence, "begin" j begin, "end" j end]
        fun documentsHref(): String = "/documents#${DocumentSurface.projectFragment(project)}/${WikiSurface.encode(document)}"
        fun href(): String {
            val values = s_[receiptCid, project, document, originalCid, revision, sequence.toString(), begin.toString(), end.toString()]
            return buildString {
                append("/panels?load=").append(PRESET)
                for (i in 0 until keys.size) append('&').append(keys[i]).append('=').append(WikiSurface.encode(values[i]))
            }.also { require(it.length <= MAX_QUERY) { "English navigation link exceeds its budget" } }
        }
    }

    private const val MAX_SAFE = 9_007_199_254_740_991L
    private fun text(value: String): String = value.also {
        require(it.isNotBlank() && it.length <= 1024 && it.none { c -> c < ' ' || c == '\u007f' }) { "Invalid English navigation text" }
        it.encodeToByteArray(throwOnInvalidSequence = true)
    }
    private fun number(value: Any?, max: Long): Long {
        val n = (value as? Number)?.toDouble() ?: error("English navigation ordinal required")
        require(n.isFinite() && n >= 0 && n <= max.toDouble() && n == n.toLong().toDouble()) { "Invalid English navigation ordinal" }
        return n.toLong()
    }
    private fun decimal(value: String, max: Long): Long {
        require(value.isNotEmpty() && (value == "0" || value.first() in '1'..'9')) { "Canonical decimal navigation field required" }
        val input = CharSeries(value)
        var result = 0L
        while (input.hasRemaining) {
            val c = input.get
            require(c in '0'..'9' && result <= (max - (c - '0')) / 10) { "English navigation ordinal exceeds its bound" }
            result = result * 10 + (c - '0')
        }
        return result
    }

    /** Use the already validated English wire payload; this does not reverify its producer. */
    fun from(inspection: Map<*, *>): Selection {
        require(inspection["schema"] == "trikeshed.english-inspection.v1" && inspection["reviewStatus"] == "unreviewed" &&
            inspection["authority"] == "lexical-and-parser-observations-only") { "English inspection required" }
        val source = inspection["selection"] as? Map<*, *> ?: error("English inspection selection missing")
        val range = inspection["range"] as? Map<*, *> ?: error("English inspection range missing")
        fun string(row: Map<*, *>, key: String) = row[key] as? String ?: error("English navigation $key missing")
        val original = string(inspection, "originalCid")
        require(source["cid"] == original) { "English inspection original identity differs" }
        val begin = number(range["begin"], Int.MAX_VALUE.toLong()).toInt()
        val end = number(range["end"], Int.MAX_VALUE.toLong()).toInt()
        val total = number(range["totalLength"], 4 * 1024 * 1024L)
        require(end <= total && range["complete"] == (begin == 0 && end.toLong() == total)) { "English inspection range differs" }
        return Selection(string(inspection, "receiptCid"), string(source, "project"), string(source, "id"),
            original, string(source, "rev"), number(source["seq"], MAX_SAFE), begin, end)
    }
    fun href(inspection: Map<*, *>): String = from(inspection).href()

    private class Query(val fields: Map<String, List<String>>, val invalid: Boolean, val english: Boolean, val copular: Boolean)
    /** query = ['?'] pair {'&' pair}; pair = component '=' component. Repeats remain visible. */
    private fun query(raw: String): Query {
        require(raw.length <= MAX_QUERY) { "English navigation query exceeds its budget" }
        val input = CharSeries(raw); input.consume('?')
        val fields = mutableMapOf<String, MutableList<String>>()
        var invalid = false; var english = false; var copular = false; var count = 0
        fun component(key: Boolean): String = buildString {
            while (input.hasRemaining && input[input.pos] != '&' && (!key || input[input.pos] != '=')) append(input.get)
        }
        while (input.hasRemaining) {
            require(++count <= 32) { "English navigation field count exceeds its budget" }
            val rawKey = component(true)
            val equals = input.consume('=')
            val rawValue = if (equals) component(false) else ""
            val key = runCatching { decode(rawKey) }.getOrNull()
            val value = runCatching { decode(rawValue) }.getOrNull()
            if (rawKey.startsWith("english") || key?.startsWith("english") == true) english = true
            if (key == "load" && value == PRESET) copular = true
            if (!equals || rawKey.isEmpty() || key == null || value == null) invalid = true
            else fields.getOrPut(key) { mutableListOf() }.add(value)
            if (input.hasRemaining) { require(input.consume('&')); if (!input.hasRemaining) invalid = true }
        }
        return Query(fields, invalid, english, copular)
    }
    /** Decode percent octets as strict UTF-8; do not let browser replacement characters select a source. */
    private fun decode(raw: String): String {
        require(raw.length <= MAX_QUERY) { "English navigation component exceeds its budget" }
        val input = CharSeries(raw)
        val bytes = mutableListOf<Byte>()
        while (input.hasRemaining) {
            val c = input.get
            require(c.code in 0x21..0x7e && c != '#') { "Navigation fields require encoded UTF-8" }
            if (c == '%') {
                require(input.rem >= 2) { "Incomplete navigation percent octet" }
                val a = input.get.digitToIntOrNull(16); val b = input.get.digitToIntOrNull(16)
                require(a != null && b != null) { "Invalid navigation percent octet" }
                bytes.add((a * 16 + b).toByte())
            } else bytes.add((if (c == '+') ' ' else c).code.toByte())
        }
        return bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
    }
    /** Recognition only. A malformed copular handoff must not fall through to scratch restoration. */
    fun requested(raw: String): Boolean = try {
        query(raw).let { it.english || it.invalid && it.copular }
    } catch (_: Exception) {
        // A budget failure can hide a percent-encoded English key. Keep it inert rather
        // than allowing URLSearchParams' get-first path to restore scratch or a preset.
        true
    }

    fun parse(raw: String): Selection {
        val query = query(raw)
        require(!query.invalid && query.fields.keys == keys.view.toSet() + "load" &&
            query.fields.values.all { it.size == 1 } && query.fields["load"]?.single() == PRESET) {
            "Choose one complete English inspection handoff and the offered copular construction"
        }
        fun one(key: String) = requireNotNull(query.fields[key]).single()
        return Selection(one("englishReceipt"), one("englishProject"), one("englishDocument"),
            one("englishCid"), one("englishRev"), decimal(one("englishSeq"), MAX_SAFE),
            decimal(one("englishBegin"), Int.MAX_VALUE.toLong()).toInt(), decimal(one("englishEnd"), Int.MAX_VALUE.toLong()).toInt())
    }
}
