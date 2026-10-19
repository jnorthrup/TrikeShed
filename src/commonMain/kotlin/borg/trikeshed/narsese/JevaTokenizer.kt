package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.parse.reifyStrict

/** Qwen's NFC, Unicode split and byte-level BPE. No chat template or implicit special tokens. */
class JevaTokenizer(
    val vocabulary: Map<String, Int>,
    merges: Series<Twin<String>>,
    val special: Map<String, Int> = emptyMap(),
) {
    val ranks = HashMap<String, Int>()
    val bytes = CharArray(256)

    init {
        var extra = 256
        for (byte in 0..255) bytes[byte] = if (byte in 33..126 || byte in 161..172 || byte in 174..255)
            byte.toChar() else (extra++).toChar()
        require(vocabulary.values.all { it >= 0 } && vocabulary.values.toSet().size == vocabulary.size)
        for (i in 0 until merges.size) {
            val pair = merges[i]
            require(pair.a in vocabulary && pair.b in vocabulary && pair.a + pair.b in vocabulary) { "Invalid Jeva BPE merge" }
            require(ranks.put(pair.a + '\u0000' + pair.b, i) == null) { "Duplicate Jeva BPE merge" }
        }
        require(special.keys.none { it.isEmpty() } && special.values.all { it >= 0 })
    }

    fun encode(text: String, maxLength: Int = 9000, check: () -> Unit = {}): Series<Int> {
        check()
        require(maxLength > 0 && text.length <= Jeva.MAX_TEXT_CHARS)
        val result = ArrayList<Int>()
        fun plain(value: String) {
            for (piece in split(normalize(value, check), check).view) {
                check()
                val symbols = piece.encodeToByteArray().mapTo(ArrayList()) { bytes[it.toInt() and 255].toString() }
                while (symbols.size > 1) {
                    check()
                    var best = -1
                    var rank = Int.MAX_VALUE
                    for (i in 0 until symbols.size - 1) {
                        if (i and 4095 == 0) check()
                        val candidate = ranks[symbols[i] + '\u0000' + symbols[i + 1]] ?: continue
                        if (candidate < rank) { rank = candidate; best = i }
                    }
                    if (best < 0) break
                    symbols[best] += symbols.removeAt(best + 1)
                }
                for (symbol in symbols) {
                    if (result.size and 4095 == 0) check()
                    result.add(requireNotNull(vocabulary[symbol]) { "Jeva vocabulary lacks byte sequence" })
                    require(result.size <= maxLength) { "Jeva input exceeds $maxLength tokens; truncation is disabled" }
                }
            }
        }
        var at = 0
        var from = 0
        while (at < text.length) {
            if (at and 4095 == 0) check()
            val added = special.keys.asSequence().filter { text.startsWith(it, at) }.maxByOrNull { it.length }
            if (added != null) {
                plain(text.substring(from, at)); result.add(special.getValue(added))
                require(result.size <= maxLength) { "Jeva input exceeds $maxLength tokens; truncation is disabled" }
                at += added.length; from = at
            } else at += BertWordPiece.scalarWidth(BertWordPiece.scalar(text, at))
        }
        plain(text.substring(from))
        check()
        return result.toIntArray().toSeries()
    }

    companion object {
        const val PATTERN = "(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\\r\\n\\p{L}\\p{N}]?[\\p{L}\\p{M}]+|\\p{N}| ?[^\\s\\p{L}\\p{M}\\p{N}]+[\\r\\n]*|\\s*[\\r\\n]+|\\s+(?!\\S)|\\s+"
        fun parse(json: String, check: () -> Unit = {}): JevaTokenizer {
            check()
            val root = requireNotNull(reifyStrict(json) as? Map<*, *>) { "Invalid Jeva tokenizer" }
            require((root["normalizer"] as? Map<*, *>)?.get("type") == "NFC") { "Jeva requires NFC tokenizer" }
            val sequence = (root["pre_tokenizer"] as? Map<*, *>)
            val parts = sequence?.get("pretokenizers") as? List<*>
            require(sequence?.get("type") == "Sequence" && parts?.size == 2) { "Unsupported Jeva pretokenizer" }
            val split = parts[0] as? Map<*, *>
            val byte = parts[1] as? Map<*, *>
            require(split?.get("type") == "Split" && split["behavior"] == "Isolated" && split["invert"] == false &&
                (split["pattern"] as? Map<*, *>)?.get("Regex") == PATTERN) { "Unsupported Jeva token split" }
            require(byte?.get("type") == "ByteLevel" && byte["add_prefix_space"] == false && byte["use_regex"] == false)
            require((root["post_processor"] as? Map<*, *>)?.get("type") == "ByteLevel") { "Unsupported Jeva postprocessor" }
            val model = requireNotNull(root["model"] as? Map<*, *>)
            require(model["type"] == "BPE" && model["dropout"] == null && model["unk_token"] == null &&
                model["continuing_subword_prefix"] == "" && model["end_of_word_suffix"] == "" &&
                model["byte_fallback"] == false && model["ignore_merges"] == false) { "Unsupported Jeva BPE configuration" }
            fun id(value: Any?): Int {
                val number = requireNotNull((value as? Number)?.toDouble()) { "Missing Jeva token id" }
                require(number.isFinite() && number in 0.0..Int.MAX_VALUE.toDouble() && number % 1.0 == 0.0) {
                    "Invalid Jeva token id"
                }
                return number.toInt()
            }
            val vocabulary = requireNotNull(model["vocab"] as? Map<*, *>).entries.associate { (k, v) ->
                check(); (k as String) to id(v)
            }
            val merges = requireNotNull(model["merges"] as? List<*>).map { row ->
                check()
                val pair = row as? List<*> ?: error("Jeva merge must contain two symbols")
                require(pair.size == 2); (pair[0] as String) j (pair[1] as String)
            }.toSeries()
            val special = requireNotNull(root["added_tokens"] as? List<*>).associate { entry ->
                val token = entry as Map<*, *>
                require(token["single_word"] == false && token["lstrip"] == false && token["rstrip"] == false &&
                    token["normalized"] == false && token["special"] is Boolean) { "Unsupported Jeva added token" }
                (token["content"] as String) to id(token["id"])
            }
            return JevaTokenizer(vocabulary, merges, special).also { check() }
        }

        val letters by lazy { BertUnicode.ranges(JevaUnicodeData.LETTERS) }
        val marks by lazy { BertUnicode.ranges(JevaUnicodeData.MARKS) }
        val numbers by lazy { BertUnicode.ranges(JevaUnicodeData.NUMBERS) }
        val combiningClasses by lazy { JevaUnicodeData.CCC.split(';').associate { row ->
            val pair = row.split('='); pair[0].toInt(16) to pair[1].toInt()
        } }
        fun combiningClass(cp: Int): Int = combiningClasses[cp] ?: 0
        val decompositions by lazy { JevaUnicodeData.DECOMPOSITION.split(';').associate { row ->
            val pair = row.split('='); pair[0].toInt(16) to pair[1].split(',').map { it.toInt(16) }.toIntArray()
        } }
        val compositions by lazy { JevaUnicodeData.COMPOSITION.split(';').associate { row ->
            val fields = row.split(',').map { it.toInt(16) }; ((fields[0].toLong() shl 21) or fields[1].toLong()) to fields[2]
        } }
        fun letter(cp: Int) = BertUnicode.contains(letters, cp)
        fun mark(cp: Int) = BertUnicode.contains(marks, cp)
        fun number(cp: Int) = BertUnicode.contains(numbers, cp)
        fun space(cp: Int) = cp in 9..13 || cp == 32 || cp == 0x85 || cp == 0xa0 || cp == 0x1680 ||
            cp in 0x2000..0x200a || cp == 0x2028 || cp == 0x2029 || cp == 0x202f || cp == 0x205f || cp == 0x3000

        fun normalize(text: String, check: () -> Unit = {}): String {
            check()
            if (text.all { it.code < 128 }) return text
            val buffer = ArrayList<Int>()
            var at = 0
            while (at < text.length) {
                if (at and 4095 == 0) check()
                val cp = BertWordPiece.scalar(text, at); at += BertWordPiece.scalarWidth(cp)
                if (cp in 0xac00..0xd7a3) {
                    val s = cp - 0xac00; buffer.add(0x1100 + s / 588); buffer.add(0x1161 + s % 588 / 28)
                    if (s % 28 != 0) buffer.add(0x11a7 + s % 28)
                } else decompositions[cp]?.let { buffer.addAll(it.asIterable()) } ?: buffer.add(cp)
            }
            for (i in 1 until buffer.size) {
                if (i and 4095 == 0) check()
                val cc = combiningClass(buffer[i])
                if (cc == 0) continue
                var j = i
                while (j > 0 && combiningClass(buffer[j - 1]) > cc) {
                    if (j and 4095 == 0) check()
                    val prior = buffer[j - 1]; buffer[j - 1] = buffer[j]; buffer[j] = prior; j--
                }
            }
            if (buffer.isEmpty()) return ""
            var starter = 0; var used = 1; var previous = combiningClass(buffer[0])
            for (i in 1 until buffer.size) {
                if (i and 4095 == 0) check()
                val cp = buffer[i]; val cc = combiningClass(cp); val a = buffer[starter]
                val combined = when {
                    a in 0x1100..0x1112 && cp in 0x1161..0x1175 -> 0xac00 + ((a - 0x1100) * 21 + cp - 0x1161) * 28
                    a in 0xac00..0xd7a3 && (a - 0xac00) % 28 == 0 && cp in 0x11a8..0x11c2 -> a + cp - 0x11a7
                    else -> compositions[(a.toLong() shl 21) or cp.toLong()] ?: -1
                }
                if (combined >= 0 && (previous == 0 || previous < cc)) buffer[starter] = combined
                else { if (cc == 0) starter = used; buffer[used++] = cp; previous = cc }
            }
            return buildString { for (i in 0 until used) append(BertWordPiece.scalarString(buffer[i])) }
        }

        /** The pinned regex expressed over Unicode scalars so JS/native/JVM split identically. */
        fun split(text: String, check: () -> Unit = {}): Series<String> {
            check()
            val bounds = BertWordPiece.scalarBounds(text)
            val cp = IntArray(bounds.size - 1) { BertWordPiece.scalar(text, bounds[it]) }
            val result = ArrayList<String>(); var at = 0
            while (at < cp.size) {
                check()
                val start = at
                val contraction = if (cp[at] == 39) listOf("s", "t", "re", "ve", "m", "ll", "d").firstOrNull {
                    text.regionMatches(bounds[at] + 1, it, 0, it.length, ignoreCase = true)
                } else null
                if (contraction != null) at += contraction.length + 1
                else {
                    var word = at
                    if (cp[word] != 13 && cp[word] != 10 && !letter(cp[word]) && !number(cp[word])) word++
                    if (word < cp.size && (letter(cp[word]) || mark(cp[word]))) {
                        at = word + 1; while (at < cp.size && (letter(cp[at]) || mark(cp[at]))) {
                            if (at and 4095 == 0) check(); at++
                        }
                    } else if (letter(cp[at]) || mark(cp[at])) {
                        at++; while (at < cp.size && (letter(cp[at]) || mark(cp[at]))) {
                            if (at and 4095 == 0) check(); at++
                        }
                    } else if (number(cp[at])) at++
                    else {
                        var symbol = at + if (cp[at] == 32) 1 else 0
                        if (symbol < cp.size && !space(cp[symbol]) && !letter(cp[symbol]) && !mark(cp[symbol]) && !number(cp[symbol])) {
                            at = symbol + 1
                            while (at < cp.size && !space(cp[at]) && !letter(cp[at]) && !mark(cp[at]) && !number(cp[at])) {
                                if (at and 4095 == 0) check(); at++
                            }
                            while (at < cp.size && (cp[at] == 13 || cp[at] == 10)) {
                                if (at and 4095 == 0) check(); at++
                            }
                        } else {
                            var end = at; var newline = -1
                            while (end < cp.size && space(cp[end])) {
                                if (end and 4095 == 0) check()
                                if (cp[end] == 13 || cp[end] == 10) newline = end; end++
                            }
                            at = when { newline >= 0 -> newline + 1; end == cp.size -> end; end - start > 1 -> end - 1; else -> maxOf(end, start + 1) }
                        }
                    }
                }
                result.add(text.substring(bounds[start], bounds[at]))
            }
            return result.toTypedArray().toSeries()
        }
    }
}
