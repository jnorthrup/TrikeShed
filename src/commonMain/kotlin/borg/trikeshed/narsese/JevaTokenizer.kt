package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.parse.reifyStrict

/** Qwen's NFC, Unicode split and byte-level BPE. No chat template or implicit special tokens. */
class JevaTokenizer internal constructor(
    val vocabulary: Map<String, Int>,
    merges: Series<Twin<String>>,
    val special: Map<String, Int>,
    val ranks: HashMap<String, Int>,
) {
    constructor(vocabulary: Map<String, Int>, merges: Series<Twin<String>>, special: Map<String, Int> = emptyMap()) :
        this(vocabulary, merges, special, HashMap())

    val bytes = CharArray(256)

    init {
        require(ranks.isEmpty()) { "Jeva BPE ranks must start empty" }
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
        val alphabet = Array(bytes.size) { bytes[it].toString() }
        fun plain(value: String) {
            for (piece in split(normalize(value, check), check).view) {
                check()
                merge(piece, alphabet, result, maxLength, check)
            }
        }
        var at = 0
        var from = 0
        var budget = 0
        while (at < text.length) {
            if (budget <= 0) { check(); budget = 4096 }
            val prior = at
            val added = special.keys.asSequence().filter { text.startsWith(it, at) }.maxByOrNull { it.length }
            if (added != null) {
                plain(text.substring(from, at)); result.add(special.getValue(added))
                require(result.size <= maxLength) { "Jeva input exceeds $maxLength tokens; truncation is disabled" }
                at += added.length; from = at
            } else at += BertWordPiece.scalarWidth(BertWordPiece.scalar(text, at))
            budget -= at - prior
        }
        plain(text.substring(from))
        check()
        return result.toIntArray().toSeries()
    }

    /** Adjacent pair candidates are ordered by merge rank and then original left position. */
    private fun merge(piece: String, alphabet: Array<String>, result: MutableList<Int>, maxLength: Int, check: () -> Unit) {
        if (piece.length == 1 && piece[0].code < 128) {
            if (result.size and 4095 == 0) check()
            result.add(requireNotNull(vocabulary[alphabet[piece[0].code]]) { "Jeva vocabulary lacks byte sequence" })
            require(result.size <= maxLength) { "Jeva input exceeds $maxLength tokens; truncation is disabled" }
            return
        }
        val encoded = piece.encodeToByteArray()
        if (encoded.isEmpty()) return
        val symbols = Array(encoded.size) { i ->
            if (i and 4095 == 0) check()
            alphabet[encoded[i].toInt() and 255]
        }
        val previous = IntArray(encoded.size) { it - 1 }
        val next = IntArray(encoded.size) { if (it + 1 < encoded.size) it + 1 else -1 }
        val versions = IntArray(encoded.size)
        val candidates = MergeQueue(minOf(encoded.size, 1024))
        fun offer(left: Int) {
            if (left < 0) return
            val right = next[left]
            if (right < 0) return
            val rank = ranks[symbols[left] + '\u0000' + symbols[right]] ?: return
            candidates.add(left, right, versions[left], versions[right], rank)
        }
        for (i in 0 until encoded.size - 1) {
            if (i and 4095 == 0) check()
            offer(i)
        }
        while (candidates.isNotEmpty()) {
            check()
            val pair = candidates.remove()
            val left = pair.left
            val right = pair.right
            if (next[left] != right || versions[left] != pair.leftVersion || versions[right] != pair.rightVersion) continue
            symbols[left] += symbols[right]
            symbols[right] = ""
            versions[left]++
            versions[right]++
            val after = next[right]
            next[left] = after
            if (after >= 0) previous[after] = left
            next[right] = -1
            offer(previous[left])
            offer(left)
        }
        var at = 0
        while (at >= 0) {
            if (result.size and 4095 == 0) check()
            result.add(requireNotNull(vocabulary[symbols[at]]) { "Jeva vocabulary lacks byte sequence" })
            require(result.size <= maxLength) { "Jeva input exceeds $maxLength tokens; truncation is disabled" }
            at = next[at]
        }
    }

    private data class Merge(val left: Int, val right: Int, val leftVersion: Int, val rightVersion: Int, val rank: Int)

    /** A compact binary heap avoids allocating one object for every queued pair. */
    private class MergeQueue(capacity: Int) {
        private var left = IntArray(maxOf(1, capacity))
        private var right = IntArray(left.size)
        private var leftVersion = IntArray(left.size)
        private var rightVersion = IntArray(left.size)
        private var rank = IntArray(left.size)
        private var size = 0

        fun isNotEmpty() = size != 0

        private fun before(r: Int, l: Int, index: Int): Boolean = r < rank[index] || (r == rank[index] && l < left[index])
        private fun copy(from: Int, to: Int) {
            left[to] = left[from]; right[to] = right[from]
            leftVersion[to] = leftVersion[from]; rightVersion[to] = rightVersion[from]; rank[to] = rank[from]
        }
        private fun set(index: Int, l: Int, r: Int, lv: Int, rv: Int, priority: Int) {
            left[index] = l; right[index] = r
            leftVersion[index] = lv; rightVersion[index] = rv; rank[index] = priority
        }

        fun add(l: Int, r: Int, lv: Int, rv: Int, priority: Int) {
            if (size == left.size) {
                val capacity = left.size * 2
                left = left.copyOf(capacity); right = right.copyOf(capacity)
                leftVersion = leftVersion.copyOf(capacity); rightVersion = rightVersion.copyOf(capacity); rank = rank.copyOf(capacity)
            }
            var at = size++
            while (at > 0) {
                val parent = (at - 1) / 2
                if (!before(priority, l, parent)) break
                copy(parent, at)
                at = parent
            }
            set(at, l, r, lv, rv, priority)
        }

        fun remove(): Merge {
            check(size > 0)
            val result = Merge(left[0], right[0], leftVersion[0], rightVersion[0], rank[0])
            val last = --size
            if (last > 0) {
                val tailLeft = left[last]; val tailRight = right[last]
                val tailLeftVersion = leftVersion[last]; val tailRightVersion = rightVersion[last]; val tailRank = rank[last]
                var at = 0
                while (at * 2 + 1 < size) {
                    var child = at * 2 + 1
                    if (child + 1 < size && before(rank[child + 1], left[child + 1], child)) child++
                    if (tailRank < rank[child] || (tailRank == rank[child] && tailLeft <= left[child])) break
                    copy(child, at)
                    at = child
                }
                set(at, tailLeft, tailRight, tailLeftVersion, tailRightVersion, tailRank)
            }
            return result
        }
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
            if (ascii(text, check)) return text
            return normalizeNonAscii(text, combiningClasses, check)
        }

        internal fun normalize(text: String, classes: Map<Int, Int>, check: () -> Unit = {}): String {
            check()
            if (ascii(text, check)) return text
            return normalizeNonAscii(text, classes, check)
        }

        private fun ascii(text: String, check: () -> Unit): Boolean {
            for (i in text.indices) {
                if (i and 4095 == 0) check()
                if (text[i].code >= 128) return false
            }
            return true
        }

        private fun normalizeNonAscii(text: String, classes: Map<Int, Int>, check: () -> Unit): String {
            fun combining(cp: Int) = classes[cp] ?: 0
            val buffer = ArrayList<Int>()
            var at = 0
            var budget = 0
            while (at < text.length) {
                if (budget <= 0) { check(); budget = 4096 }
                val prior = at
                val cp = BertWordPiece.scalar(text, at); at += BertWordPiece.scalarWidth(cp)
                if (cp in 0xac00..0xd7a3) {
                    val s = cp - 0xac00; buffer.add(0x1100 + s / 588); buffer.add(0x1161 + s % 588 / 28)
                    if (s % 28 != 0) buffer.add(0x11a7 + s % 28)
                } else decompositions[cp]?.let { buffer.addAll(it.asIterable()) } ?: buffer.add(cp)
                budget -= at - prior
            }
            // Canonical order is a stable sort within each run of non-starters.
            // Bounded insertion is cheap for short runs; counting keeps long
            // disordered runs linear rather than repeatedly shifting every mark.
            var start = 0
            var scratch = IntArray(0)
            var counts: IntArray? = null
            while (start < buffer.size) {
                if (start and 4095 == 0) check()
                if (combining(buffer[start]) == 0) start++
                val from = start
                var last = 0
                var disordered = false
                while (start < buffer.size) {
                    if (start and 4095 == 0) check()
                    val cc = combining(buffer[start])
                    if (cc == 0) break
                    if (cc < last) disordered = true
                    last = cc
                    start++
                }
                val length = start - from
                if (!disordered) continue
                if (length <= 32) {
                    for (i in from + 1 until start) {
                        val value = buffer[i]
                        val cc = combining(value)
                        var j = i
                        while (j > from && combining(buffer[j - 1]) > cc) {
                            buffer[j] = buffer[j - 1]
                            j--
                        }
                        buffer[j] = value
                    }
                } else {
                    val offsets = counts ?: IntArray(256).also { counts = it }
                    offsets.fill(0)
                    for (i in from until start) {
                        if (i and 4095 == 0) check()
                        offsets[combining(buffer[i])]++
                    }
                    var total = 0
                    for (i in offsets.indices) {
                        val count = offsets[i]
                        offsets[i] = total
                        total += count
                    }
                    if (scratch.size < length) scratch = IntArray(length)
                    for (i in from until start) {
                        if (i and 4095 == 0) check()
                        val cp = buffer[i]
                        scratch[offsets[combining(cp)]++] = cp
                    }
                    for (i in 0 until length) {
                        if (i and 4095 == 0) check()
                        buffer[from + i] = scratch[i]
                    }
                }
            }
            if (buffer.isEmpty()) return ""
            var starter = 0; var used = 1; var previous = combining(buffer[0])
            for (i in 1 until buffer.size) {
                if (i and 4095 == 0) check()
                val cp = buffer[i]; val cc = combining(cp); val a = buffer[starter]
                val combined = when {
                    a in 0x1100..0x1112 && cp in 0x1161..0x1175 -> 0xac00 + ((a - 0x1100) * 21 + cp - 0x1161) * 28
                    a in 0xac00..0xd7a3 && (a - 0xac00) % 28 == 0 && cp in 0x11a8..0x11c2 -> a + cp - 0x11a7
                    else -> compositions[(a.toLong() shl 21) or cp.toLong()] ?: -1
                }
                if (combined >= 0 && (previous == 0 || previous < cc)) buffer[starter] = combined
                else { if (cc == 0) starter = used; buffer[used++] = cp; previous = cc }
            }
            return normalizedText(buffer, used, check)
        }

        internal fun normalizedText(buffer: List<Int>, used: Int, check: () -> Unit): String =
            buildString {
                for (i in 0 until used) {
                    if (i and 4095 == 0) check()
                    append(BertWordPiece.scalarString(buffer[i]))
                }
            }

        /** The pinned regex expressed over Unicode scalars so JS/native/JVM split identically. */
        fun split(text: String, check: () -> Unit = {}): Series<String> {
            check()
            val bounds = scalarBounds(text, check)
            val cp = scalars(text, bounds, check)
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

        internal fun scalarBounds(text: String, check: () -> Unit): IntArray {
            val bounds = IntArray(text.length + 1)
            var at = 0
            var count = 0
            var budget = 0
            while (at < text.length) {
                if (budget <= 0) { check(); budget = 4096 }
                bounds[count++] = at
                val width = BertWordPiece.scalarWidth(BertWordPiece.scalar(text, at))
                at += width
                budget -= width
            }
            bounds[count] = at
            return bounds.copyOf(count + 1)
        }

        internal fun scalars(text: String, bounds: IntArray, check: () -> Unit): IntArray =
            IntArray(bounds.size - 1) {
                if (it and 4095 == 0) check()
                BertWordPiece.scalar(text, bounds[it])
            }
    }
}
