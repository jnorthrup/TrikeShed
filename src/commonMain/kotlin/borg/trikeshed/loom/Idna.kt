package borg.trikeshed.loom

/**
 * idna 1.1.0 `domain_to_ascii_cow(domain, AsciiDenyList::URL)`, the call url 2.5.8 `Host::parse` makes on a special
 * URL's percent-decoded host: [Uts46.to_ascii]. null is `Err(Errors)`.
 */
fun domain_to_ascii_cow(domain: ByteArray): String? = Uts46.to_ascii(domain)

/**
 * idna 1.1.0 uts46.rs `Uts46::to_ascii` as lib.rs configures it (`AsciiDenyList::URL`, `Hyphens::Allow`,
 * `DnsLength::Ignore`), i.e. `Uts46::process` under `ErrorPolicy::FailFast`: the first error refuses the domain.
 * UTS 46 ToASCII, nontransitional, CheckHyphens and VerifyDnsLength off, CheckBidi and CheckJoiners on, the WHATWG
 * forbidden domain code points denied; [IdnaAdapter] supplies the mapping, NFC and the Unicode properties.
 */
object Uts46 {
    /** ICU4C-compatible constraints on Punycode input (ICU-13727). */
    const val PUNYCODE_DECODE_MAX_INPUT_LENGTH = 2000
    const val PUNYCODE_ENCODE_MAX_INPUT_LENGTH = 1000

    /** U+FFFD, which `process` writes for every error. */
    const val REPLACEMENT_CHARACTER = 0xFFFD

    /** `AsciiDenyList::URL`: ASCII upper case, U+0020 and below, U+007F and the forbidden domain code points. */
    fun denied(c: Int): Boolean =
        c < 0x80 && (c <= 0x20 || c == 0x7f || c in 'A'.code..'Z'.code || c.toChar() in "%#/:<>?@[\\]^|")

    /** Input known to be ASCII, but may contain upper case ASCII. */
    fun apply_ascii_deny_list_to_potentially_upper_case_ascii(b: Int): Int =
        if (!denied(b)) b else if (b in 'A'.code..'Z'.code) b + 0x20 else REPLACEMENT_CHARACTER

    /** Input known to be lower case, but may contain non-ASCII; [deny_dot] adds the dot (`deny_list_deny_dot`). */
    fun apply_ascii_deny_list_to_lower_cased_unicode(c: Int, deny_dot: Boolean): Int =
        if (denied(c) || (deny_dot && c == '.'.code)) REPLACEMENT_CHARACTER else c

    /** A label passed through as is: empty, or `[a-z][a-z0-9-]*` not ending in `-` and without `--` at index 2. */
    fun is_passthrough_ascii_label(label: ByteArray): Boolean {
        if (label.size >= 4 && label[2] == '-'.code.toByte() && label[3] == '-'.code.toByte()) return false
        if (label.isEmpty()) return true
        if (label[0].toInt().toChar() !in 'a'..'z') return false
        for (b in label) {
            val c = b.toInt().toChar()
            if (!(c in 'a'..'z' || c in '0'..'9' || c == '-')) return false
        }
        return label.last() != '-'.code.toByte()
    }

    /** Where the label's non-ASCII suffix starts: one ASCII byte before its first non-ASCII byte, or its end. */
    fun split_ascii_fast_path_prefix(label: ByteArray): Int {
        val pos = label.indexOfFirst { it < 0 }
        return if (pos < 0) label.size else maxOf(pos - 1, 0)
    }

    /** `xn--` with the `x` and `n` in either case. */
    fun has_punycode_prefix(slice: ByteArray): Boolean = slice.size >= 4 && slice[0].toInt() and 0xdf == 'X'.code &&
        slice[1].toInt() and 0xdf == 'N'.code && slice[2] == '-'.code.toByte() && slice[3] == '-'.code.toByte()

    fun is_ascii(label: List<Int>): Boolean = label.all { it < 0x80 }

    /**
     * `to_ascii`: `process_inner`'s passthrough of a `[a-z.]` domain, `process_innermost` over the labels after it
     * filling the domain buffer, the bidi rule, then the labels written back: the passthrough prefix as is, an ASCII
     * label as is, a label that came in as Punycode as its input in lower case, any other as `xn--` and its Punycode.
     */
    fun to_ascii(domain_name: ByteArray): String? {
        var tail = 0
        var i = 0
        while (i < domain_name.size) {
            val b = domain_name[i]
            if (b in 'a'.code.toByte()..'z'.code.toByte()) i++
            else if (b == '.'.code.toByte()) tail = ++i
            else break
        }
        if (i == domain_name.size) return domain_name.decodeToString()
        val domain_buffer = ArrayList<Int>()
        // Per domain-buffer label, its input when it came in as Punycode (a mixed-case ASCII label is its lower case).
        val already_punycode = ArrayList<ByteArray?>()
        var passthrough_up_to = tail
        var seen_label = false
        var in_prefix = true
        var start = tail
        while (start <= domain_name.size) {
            var end = start
            while (end < domain_name.size && domain_name[end] != '.'.code.toByte()) end++
            val label = domain_name.copyOfRange(start, end)
            start = end + 1
            if (in_prefix && is_passthrough_ascii_label(label)) {
                if (seen_label) passthrough_up_to++
                seen_label = true
                passthrough_up_to += label.size
                continue
            }
            if (seen_label && in_prefix) passthrough_up_to++
            else if (seen_label) domain_buffer.add('.'.code)
            seen_label = true
            in_prefix = false
            var current_label_start = domain_buffer.size
            if (label.isEmpty()) {
                already_punycode.add(null)
                continue
            }
            val split = split_ascii_fast_path_prefix(label)
            if (split == label.size && has_punycode_prefix(label)) {
                if (label.last() == '-'.code.toByte() || label.size - 4 > PUNYCODE_DECODE_MAX_INPUT_LENGTH) return null
                val decode = Punycode.decode(IntArray(label.size - 4) { label[it + 4].toInt() }) ?: return null
                if (!after_punycode_decode(domain_buffer, current_label_start, decode)) return null
                if (!check_label(domain_buffer.subList(current_label_start, domain_buffer.size), true, true)) return null
                already_punycode.add(label)
                continue
            }
            for (j in 0 until split) apply_ascii_deny_list_to_potentially_upper_case_ascii(label[j].toInt()).let {
                if (it == REPLACEMENT_CHARACTER) return null
                domain_buffer.add(it)
            }
            already_punycode.add(null)
            if (split == label.size) continue
            var first_needs_combining_mark_check = split == 0
            var needs_contextj_check = true
            // utf8_iter reads ill-formed UTF-8 as U+FFFD, which the mapping keeps and the loop below refuses.
            val mapping = IdnaAdapter.map_normalize(utf8(label, split) ?: return null)
            var next = 0
            while (true) {
                val n = if (next < mapping.size) apply_ascii_deny_list_to_lower_cased_unicode(mapping[next++], false) else -1
                if (n != -1 && n != '.'.code) {
                    if (n == REPLACEMENT_CHARACTER) return null
                    domain_buffer.add(n)
                    continue
                }
                val current = domain_buffer.subList(current_label_start, domain_buffer.size)
                if (current.size >= 4 && current.subList(0, 4) == "xn--".map { it.code }) {
                    if (current.subList(4, current.size).any { it >= 0x80 }) return null
                    if (current.last() == '-'.code) return null
                    if (current.size - 4 > PUNYCODE_DECODE_MAX_INPUT_LENGTH) return null
                    val decode = Punycode.decode(current.subList(4, current.size).toIntArray()) ?: return null
                    first_needs_combining_mark_check = true
                    needs_contextj_check = true
                    current.clear()
                    if (!after_punycode_decode(domain_buffer, current_label_start, decode)) return null
                }
                val checked = domain_buffer.subList(current_label_start, domain_buffer.size)
                if (!check_label(checked, first_needs_combining_mark_check, needs_contextj_check)) return null
                if (n == -1) break
                domain_buffer.add('.'.code)
                current_label_start = domain_buffer.size
                first_needs_combining_mark_check = true
                needs_contextj_check = true
                already_punycode.add(null)
            }
        }
        if (passthrough_up_to == domain_name.size) return domain_name.decodeToString()
        val labels = split_labels(domain_buffer)
        if (is_bidi(domain_buffer) && !labels.all { check_bidi(it) }) return null
        val out = StringBuilder(domain_name.decodeToString(0, passthrough_up_to))
        for ((index, label) in labels.withIndex()) {
            if (index > 0) out.append('.')
            val input = already_punycode[index]
            when {
                is_ascii(label) -> for (c in label) out.append(c.toChar())
                input != null -> out.append(input.decodeToString().lowercase())
                else -> Punycode.encode_into(label, out.append("xn--"))
            }
        }
        return out.toString()
    }

    /** `domain_buffer.split('.')`. */
    fun split_labels(domain_buffer: List<Int>): List<List<Int>> {
        val labels = ArrayList<List<Int>>()
        var start = 0
        while (start <= domain_buffer.size) {
            var end = start
            while (end < domain_buffer.size && domain_buffer[end] != '.'.code) end++
            labels.add(domain_buffer.subList(start, end))
            start = end + 1
        }
        return labels
    }

    /** `non_ascii.chars()`: the code points of [bytes] from [from], null where they are not well-formed UTF-8. */
    fun utf8(bytes: ByteArray, from: Int): IntArray? {
        val out = ArrayList<Int>()
        var i = from
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xff
            val count = when {
                b < 0x80 -> 0
                b in 0xc2..0xdf -> 1
                b in 0xe0..0xef -> 2
                b in 0xf0..0xf4 -> 3
                else -> return null
            }
            if (i + count >= bytes.size) return null
            var c = b and (0x7f shr (count + if (count > 0) 1 else 0))
            for (j in 1..count) {
                val t = bytes[i + j].toInt() and 0xff
                if (t and 0xc0 != 0x80) return null
                c = (c shl 6) or (t and 0x3f)
            }
            if (c < intArrayOf(0, 0x80, 0x800, 0x10000)[count] || c > 0x10ffff || c in 0xd800..0xdfff) return null
            out.add(c)
            i += count + 1
        }
        return out.toIntArray()
    }

    /** The decoded label's NFC and status check: written to the buffer, refused where it is not its own NFC. */
    fun after_punycode_decode(domain_buffer: MutableList<Int>, current_label_start: Int, label_buffer: IntArray): Boolean {
        for (c in IdnaAdapter.normalize_validate(label_buffer)) apply_ascii_deny_list_to_lower_cased_unicode(c, true).let {
            if (it == REPLACEMENT_CHARACTER) return false
            domain_buffer.add(it)
        }
        val normalized = domain_buffer.subList(current_label_start, domain_buffer.size)
        for (j in 0 until minOf(normalized.size, label_buffer.size)) if (normalized[j] != label_buffer[j]) return false
        return true
    }

    /** A leading combining mark, CONTEXTJ (RFC 5892 appendix A.1, A.2) and the Punycode encode limit. */
    fun check_label(label: List<Int>, first_needs_combining_mark_check: Boolean, needs_contextj_check: Boolean): Boolean {
        if (first_needs_combining_mark_check && label.isNotEmpty() && IdnaAdapter.is_mark(label[0])) return false
        if (needs_contextj_check) for ((i, c) in label.withIndex()) {
            if (c != 0x200c && c != 0x200d) continue
            if (i == 0) return false
            if (IdnaAdapter.is_virama(label[i - 1])) continue
            if (c == 0x200d) return false
            if (!has_appropriately_joining_char(label.subList(0, i).asReversed(), IdnaAdapter.LEFT_OR_DUAL_JOINING_MASK) ||
                !has_appropriately_joining_char(label.subList(i + 1, label.size), IdnaAdapter.RIGHT_OR_DUAL_JOINING_MASK)
            ) return false
        }
        return is_ascii(label) || label.size <= PUNYCODE_ENCODE_MAX_INPUT_LENGTH
    }

    /** The first character that is not Transparent joins as [required_mask] asks. */
    fun has_appropriately_joining_char(chars: List<Int>, required_mask: Int): Boolean {
        for (c in chars) {
            val jt = IdnaAdapter.joining_type(c)
            if ((1 shl jt) and required_mask != 0) return true
            if (jt != IdnaAdapter.TRANSPARENT) return false
        }
        return false
    }

    /** A bidi domain name: some character outside the ranges skipped here is R, AL or AN. */
    fun is_bidi(buffer: List<Int>): Boolean {
        for (c in buffer) {
            if (c < 0x0590 || c in 0x0900..0xfb1c || c in 0x1f000..0x3ffff) continue
            if (c in 0xff00..0x107ff || c in 0x11000..0x1e7ff) continue
            if ((1 shl IdnaAdapter.bidi_class(c)) and IdnaAdapter.RTL_MASK != 0) return true
        }
        return false
    }

    /** RFC 5893 section 2 over one label of a bidi domain name. */
    fun check_bidi(label: List<Int>): Boolean {
        if (label.isEmpty()) return true
        val first_bc = IdnaAdapter.bidi_class(label[0])
        if ((1 shl first_bc) and IdnaAdapter.FIRST_BC_MASK == 0) return false
        val is_ltr = first_bc == IdnaAdapter.L
        var end = label.size
        // trailing NSM trimmed
        while (end > 1 && IdnaAdapter.bidi_class(label[end - 1]) == IdnaAdapter.NSM) end--
        if (end == 1) return true
        val last_bc = IdnaAdapter.bidi_class(label[end - 1])
        if ((1 shl last_bc) and (if (is_ltr) IdnaAdapter.LAST_LTR_MASK else IdnaAdapter.LAST_RTL_MASK) == 0) return false
        val prior = label.subList(1, end - 1)
        if (is_ltr) return prior.all { (1 shl IdnaAdapter.bidi_class(it)) and IdnaAdapter.MIDDLE_LTR_MASK != 0 }
        var numeral_state = -1
        for (c in prior) {
            val bc = IdnaAdapter.bidi_class(c)
            if ((1 shl bc) and IdnaAdapter.MIDDLE_RTL_MASK == 0) return false
            if (bc == IdnaAdapter.EN || bc == IdnaAdapter.AN) {
                if (numeral_state == -1) numeral_state = bc
                else if (numeral_state != bc) return false
            }
        }
        return !(numeral_state == IdnaAdapter.EN && last_bc == IdnaAdapter.AN ||
            numeral_state == IdnaAdapter.AN && last_bc == IdnaAdapter.EN)
    }
}

/**
 * idna 1.1.0 punycode.rs: RFC 3492 Bootstring with the Punycode parameters, as uts46.rs calls it (`InternalCaller`:
 * a decoded basic code point comes out in lower case, the encoder takes at most
 * [Uts46.PUNYCODE_ENCODE_MAX_INPUT_LENGTH] code points and so never overflows).
 */
object Punycode {
    const val BASE = 36L
    const val T_MIN = 1L
    const val T_MAX = 26L
    const val SKEW = 38L
    const val DAMP = 700L
    const val INITIAL_BIAS = 72L
    const val INITIAL_N = 0x80L
    const val U32_MAX = 0xffffffffL

    fun adapt(delta: Long, num_points: Long, first_time: Boolean): Long {
        var d = delta / if (first_time) DAMP else 2
        d += d / num_points
        var k = 0L
        while (d > ((BASE - T_MIN) * T_MAX) / 2) {
            d /= BASE - T_MIN
            k += BASE
        }
        return k + (((BASE - T_MIN + 1) * d) / (d + SKEW))
    }

    fun digit(c: Int): Long = when (c) {
        in '0'.code..'9'.code -> c - '0'.code + 26L
        in 'A'.code..'Z'.code -> c - 'A'.code.toLong()
        in 'a'.code..'z'.code -> c - 'a'.code.toLong()
        else -> -1
    }

    /**
     * `Decoder::decode`: the basic code points before the last delimiter, then the insertions, with u32 overflow,
     * a truncated delta and a code point that is not a scalar value refused (null). A delimiter at the very start
     * leaves the input whole, so its `-` reads as a digit and fails.
     */
    fun decode(input: IntArray): IntArray? {
        val position = input.lastIndexOf('-'.code)
        val output = ArrayList<Int>()
        for (j in 0 until maxOf(position, 0)) output.add(input[j].let { if (it in 'A'.code..'Z'.code) it + 0x20 else it })
        var at = if (position > 0) position + 1 else 0
        var code_point = INITIAL_N
        var bias = INITIAL_BIAS
        var i = 0L
        while (at < input.size) {
            val previous_i = i
            var weight = 1L
            var k = BASE
            var byte = input[at++]
            while (true) {
                val digit = digit(byte)
                if (digit < 0) return null
                val product = digit * weight
                if (product > U32_MAX) return null
                i += product
                if (i > U32_MAX) return null
                val t = if (k <= bias) T_MIN else if (k >= bias + T_MAX) T_MAX else k - bias
                if (digit < t) break
                weight *= BASE - t
                if (weight > U32_MAX) return null
                k += BASE
                if (at == input.size) return null
                byte = input[at++]
            }
            val length = output.size.toLong()
            bias = adapt(i - previous_i, length + 1, previous_i == 0L)
            code_point += i / (length + 1)
            if (code_point > U32_MAX) return null
            i %= length + 1
            if (code_point > 0x10ffff || code_point in 0xd800L..0xdfffL) return null
            output.add(i.toInt(), code_point.toInt())
            i++
        }
        return output.toIntArray()
    }

    /** `encode_into`: [input]'s basic code points, the delimiter when there are any, then the deltas. */
    fun encode_into(input: List<Int>, output: StringBuilder) {
        var basic_length = 0L
        for (c in input) if (c < 0x80) {
            output.append(c.toChar())
            basic_length++
        }
        if (basic_length > 0) output.append('-')
        var code_point = INITIAL_N
        var delta = 0L
        var bias = INITIAL_BIAS
        var processed = basic_length
        while (processed < input.size) {
            val min_code_point = input.filter { it >= code_point }.min().toLong()
            delta += (min_code_point - code_point) * (processed + 1)
            code_point = min_code_point
            for (c in input) {
                if (c < code_point) delta++
                if (c.toLong() == code_point) {
                    var q = delta
                    var k = BASE
                    while (true) {
                        val t = if (k <= bias) T_MIN else if (k >= bias + T_MAX) T_MAX else k - bias
                        if (q < t) break
                        output.append(value_to_digit(t + (q - t) % (BASE - t)))
                        q = (q - t) / (BASE - t)
                        k += BASE
                    }
                    output.append(value_to_digit(q))
                    bias = adapt(delta, processed + 1, processed == basic_length)
                    delta = 0
                    processed++
                }
            }
            delta++
            code_point++
        }
    }

    fun value_to_digit(value: Long): Char = if (value < 26) 'a' + value.toInt() else '0' + (value.toInt() - 26)
}

/**
 * idna_adapter 1.2.2 over its ICU4X 2.3 back end (icu_normalizer `Uts46Mapper`, icu_properties maps), from the
 * compiled data in [IdnaData]: the UTS 46 mapping with NFC, Canonical_Combining_Class (Virama is class 9),
 * Bidi_Class, Joining_Type and General_Category Mark.
 */
object IdnaAdapter {
    /** Bidi_Class as ICU4C numbers it. */
    const val L = 0
    const val R = 1
    const val EN = 2
    const val ES = 3
    const val ET = 4
    const val AN = 5
    const val CS = 6
    const val ON = 10
    const val AL = 13
    const val NSM = 17
    const val BN = 18

    /** Joining_Type as ICU4C numbers it. */
    const val DUAL_JOINING = 2
    const val LEFT_JOINING = 3
    const val RIGHT_JOINING = 4
    const val TRANSPARENT = 5

    const val LEFT_OR_DUAL_JOINING_MASK = (1 shl LEFT_JOINING) or (1 shl DUAL_JOINING)
    const val RIGHT_OR_DUAL_JOINING_MASK = (1 shl RIGHT_JOINING) or (1 shl DUAL_JOINING)
    const val RTL_MASK = (1 shl R) or (1 shl AL) or (1 shl AN)
    const val FIRST_BC_MASK = (1 shl L) or (1 shl R) or (1 shl AL)
    const val LAST_LTR_MASK = (1 shl L) or (1 shl EN)
    const val LAST_RTL_MASK = (1 shl R) or (1 shl AL) or (1 shl EN) or (1 shl AN)
    const val MIDDLE_LTR_MASK =
        (1 shl L) or (1 shl EN) or (1 shl ES) or (1 shl CS) or (1 shl ET) or (1 shl ON) or (1 shl BN) or (1 shl NSM)
    const val MIDDLE_RTL_MASK = (1 shl R) or (1 shl AL) or (1 shl AN) or
        (1 shl EN) or (1 shl ES) or (1 shl CS) or (1 shl ET) or (1 shl ON) or (1 shl BN) or (1 shl NSM)

    /** An [IdnaData] stream: five bits per base64 digit, most significant first, 32 on every digit but the last. */
    class Varints(val text: String) {
        var at = 0

        fun next(): Int {
            var value = 0
            while (true) {
                val c = text[at++]
                val d = when (c) {
                    in 'A'..'Z' -> c - 'A'
                    in 'a'..'z' -> c - 'a' + 26
                    in '0'..'9' -> c - '0' + 52
                    '+' -> 62
                    else -> 63
                }
                value = (value shl 5) or (d and 31)
                if (d < 32) return value
            }
        }

        fun zigzag(): Int = next().let { (it ushr 1) xor -(it and 1) }
    }

    /** The index of the run holding [c]: the last start at or below it. */
    fun run_index(starts: IntArray, c: Int): Int {
        var low = 0
        var high = starts.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (starts[mid] <= c) low = mid else high = mid - 1
        }
        return low
    }

    /** A per-code-point property as runs of (length, value). */
    class Runs(stream: String) {
        val starts: IntArray
        val values: IntArray

        init {
            val varints = Varints(stream)
            val s = ArrayList<Int>()
            val v = ArrayList<Int>()
            var cp = 0
            while (varints.at < stream.length) {
                s.add(cp)
                cp += varints.next()
                v.add(varints.next())
            }
            starts = s.toIntArray()
            values = v.toIntArray()
        }

        operator fun get(c: Int): Int = values[run_index(starts, c)]
    }

    val ccc = Runs(IdnaData.CCC)
    val bidi = Runs(IdnaData.BIDI_CLASS)
    val joining = Runs(IdnaData.JOINING_TYPE)
    val mark = Runs(IdnaData.MARK)

    fun is_virama(c: Int): Boolean = ccc[c] == 9
    fun is_mark(c: Int): Boolean = mark[c] != 0
    fun bidi_class(c: Int): Int = bidi[c]
    fun joining_type(c: Int): Int = joining[c]

    /** [IdnaData.MAPPING] decoded: run starts, kinds, offsets (or sequence starts) and sequence lengths. */
    val mapping_starts: IntArray
    val mapping_kinds: IntArray
    val mapping_values: IntArray
    val mapping_lengths: IntArray
    val sequences: IntArray

    /** Canonical primary composites by pair (first shl 21 or second), and pairs by composite (Hangul aside). */
    val composites = HashMap<Long, Int>()
    val pairs = HashMap<Int, Long>()

    init {
        val varints = Varints(IdnaData.MAPPING)
        val starts = ArrayList<Int>()
        val kinds = ArrayList<Int>()
        val values = ArrayList<Int>()
        val lengths = ArrayList<Int>()
        val sequence = ArrayList<Int>()
        var cp = 0
        var last = 0
        while (varints.at < IdnaData.MAPPING.length) {
            val head = varints.next()
            val length = head / 6
            val kind = head % 6
            starts.add(cp)
            kinds.add(kind)
            when (kind) {
                3, 5 -> {
                    val delta = last + varints.zigzag() - cp
                    values.add(delta)
                    lengths.add(0)
                    last = cp + delta + length - if (kind == 5 && length % 2 == 0) 2 else 1
                    cp += length
                }
                4 -> {
                    values.add(sequence.size)
                    lengths.add(length)
                    repeat(length) {
                        last += varints.zigzag()
                        sequence.add(last)
                    }
                    cp++
                }
                else -> {
                    values.add(0)
                    lengths.add(0)
                    cp += length
                }
            }
        }
        mapping_starts = starts.toIntArray()
        mapping_kinds = kinds.toIntArray()
        mapping_values = values.toIntArray()
        mapping_lengths = lengths.toIntArray()
        sequences = sequence.toIntArray()
        val composition = Varints(IdnaData.COMPOSITION)
        var c = 0
        var a = 0
        var b = 0
        while (composition.at < IdnaData.COMPOSITION.length) {
            c += composition.next()
            a += composition.zigzag()
            b += composition.zigzag()
            composites[(a.toLong() shl 21) or b.toLong()] = c
            pairs[c] = (a.toLong() shl 21) or b.toLong()
        }
    }

    /** `Uts46Mapper::map_normalize`: ignored code points dropped, the rest mapped (disallowed to U+FFFD), then NFC. */
    fun map_normalize(input: IntArray): IntArray = compose(decompose(input, false))

    /** `Uts46Mapper::normalize_validate`: as [map_normalize] with ignored code points read as disallowed. */
    fun normalize_validate(input: IntArray): IntArray = compose(decompose(input, true))

    /** Each code point's mapping, fully decomposed, in canonical order. */
    fun decompose(input: IntArray, validate: Boolean): MutableList<Int> {
        val out = ArrayList<Int>()
        for (c in input) {
            val r = run_index(mapping_starts, c)
            when (mapping_kinds[r]) {
                0 -> decomposition(c, out)
                1 -> if (validate) out.add(Uts46.REPLACEMENT_CHARACTER)
                2 -> out.add(Uts46.REPLACEMENT_CHARACTER)
                3 -> decomposition(c + mapping_values[r], out)
                4 -> for (j in 0 until mapping_lengths[r]) decomposition(sequences[mapping_values[r] + j], out)
                else -> decomposition(if ((c - mapping_starts[r]) % 2 == 0) c + mapping_values[r] else c, out)
            }
        }
        for (j in 1 until out.size) {
            val cc = ccc[out[j]]
            if (cc == 0) continue
            var k = j
            while (k > 0 && ccc[out[k - 1]] > cc) {
                out[k] = out[k - 1].also { out[k - 1] = out[k] }
                k--
            }
        }
        return out
    }

    /** The canonical decomposition of a code point the mapping writes: Hangul arithmetic or the composite's pair. */
    fun decomposition(c: Int, into: MutableList<Int>) {
        if (c in 0xac00..0xd7a3) {
            val s = c - 0xac00
            into.add(0x1100 + s / 588)
            into.add(0x1161 + (s % 588) / 28)
            if (s % 28 != 0) into.add(0x11a7 + s % 28)
            return
        }
        val pair = pairs[c]
        if (pair == null) into.add(c)
        else {
            decomposition((pair ushr 21).toInt(), into)
            into.add((pair and 0x1fffff).toInt())
        }
    }

    /** The canonical composite of [a] and [b], or -1. */
    fun composite(a: Int, b: Int): Int = when {
        a in 0x1100..0x1112 && b in 0x1161..0x1175 -> 0xac00 + ((a - 0x1100) * 21 + (b - 0x1161)) * 28
        a in 0xac00..0xd7a3 && (a - 0xac00) % 28 == 0 && b in 0x11a8..0x11c2 -> a + (b - 0x11a7)
        else -> composites[(a.toLong() shl 21) or b.toLong()] ?: -1
    }

    /** Canonical composition of a decomposed, canonically ordered buffer. */
    fun compose(buffer: MutableList<Int>): IntArray {
        if (buffer.isEmpty()) return IntArray(0)
        var starter = 0
        var last_class = if (ccc[buffer[0]] == 0) 0 else 256
        var out = 1
        for (j in 1 until buffer.size) {
            val c = buffer[j]
            val cc = ccc[c]
            val composite = composite(buffer[starter], c)
            if (composite >= 0 && (last_class < cc || last_class == 0)) {
                buffer[starter] = composite
                continue
            }
            if (cc == 0) starter = out
            last_class = cc
            buffer[out++] = c
        }
        return IntArray(out) { buffer[it] }
    }
}
