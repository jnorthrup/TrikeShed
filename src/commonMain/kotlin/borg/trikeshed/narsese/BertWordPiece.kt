package borg.trikeshed.narsese

import borg.trikeshed.lib.*

/** One sentence's uncased BERT WordPieces; offsets use the source's UTF-16 coordinates. */
data class BertSentence(val text: String, val ids: Series<Int>, val offsets: Series<Twin<Int>>)

/** [target] indexes the complete target's WordPieces, excluding CLS and SEP. */
data class BertInput(
    val token: BertToken,
    val span: BertSpan,
    val ids: Series<Int>,
    val offsets: Series<Twin<Int>>,
    val target: Series<Int>,
)

/** The pinned bert-base-uncased vocabulary and tokenizer.json rules, entirely in common Kotlin. */
class BertWordPiece(vocabulary: Series<String>, val maxLength: Int = 512) {
    constructor(vocabulary: String, maxLength: Int = 512) :
        this(parseVocabulary(vocabulary), maxLength)

    val vocabulary: Map<String, Int> = buildMap {
        vocabulary.view.forEachIndexed { index, word ->
            require(word.isNotEmpty() && put(word, index) == null) { "Duplicate or empty BERT vocabulary entry" }
        }
    }
    val unknown = requireNotNull(this.vocabulary["[UNK]"]) { "BERT vocabulary lacks [UNK]" }
    val cls = requireNotNull(this.vocabulary["[CLS]"]) { "BERT vocabulary lacks [CLS]" }
    val sep = requireNotNull(this.vocabulary["[SEP]"]) { "BERT vocabulary lacks [SEP]" }
    val special = s_["[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]"]

    init { require(maxLength in 2..512) { "BERT sequence length must be in 2..512" } }

    fun sentence(text: String): BertSentence {
        require(text.length <= 16_384) { "BERT sentence exceeds 16384 UTF-16 units" }
        val pieces = ArrayList<Piece>(maxLength)
        pieces.add(Piece(cls, 0, 0))
        val word = StringBuilder()
        val starts = ArrayList<Int>()
        val ends = ArrayList<Int>()

        fun flush() {
            if (word.isNotEmpty() && pieces.size < maxLength - 1) {
                val units = word.toString()
                val bounds = scalarBounds(units)
                if (bounds.size - 1 > 100) {
                    pieces.add(Piece(unknown, starts.first(), ends.last()))
                } else {
                    val pending = ArrayList<Piece>()
                    var from = 0
                    while (from < starts.size) {
                        var to = starts.size
                        var id: Int? = null
                        while (to > from) {
                            val fragment = units.substring(bounds[from], bounds[to])
                            id = vocabulary[if (from == 0) fragment else "##$fragment"]
                            if (id != null) break
                            to--
                        }
                        if (id == null) {
                            pending.clear()
                            pending.add(Piece(unknown, starts.first(), ends.last()))
                            break
                        }
                        pending.add(Piece(id, starts[from], ends[to - 1]))
                        from = to
                    }
                    for (piece in pending) {
                        if (pieces.size == maxLength - 1) break
                        pieces.add(piece)
                    }
                }
            }
            word.clear()
            starts.clear()
            ends.clear()
        }

        fun append(cp: Int, start: Int, end: Int) {
            val punctuation = punctuation(cp)
            if (punctuation || chinese(cp)) flush()
            if (space(cp)) flush()
            else {
                word.append(scalarString(cp))
                starts.add(start)
                ends.add(end)
                if (punctuation || chinese(cp)) flush()
            }
        }

        var at = 0
        while (at < text.length) {
            val added = special.view.firstOrNull { text.startsWith(it, at) }
            if (added != null) {
                flush()
                if (pieces.size < maxLength - 1) pieces.add(Piece(requireNotNull(vocabulary[added]), at, at + added.length))
                at += added.length
                continue
            }
            val cp = scalar(text, at)
            val next = at + scalarWidth(cp)
            val normalized = normalize(cp)
            var offset = 0
            while (offset < normalized.length) {
                val n = scalar(normalized, offset)
                append(n, at, next)
                offset += scalarWidth(n)
            }
            at = next
        }
        flush()
        pieces.add(Piece(sep, 0, 0))
        val ids = IntArray(pieces.size) { pieces[it].id }
        val from = IntArray(pieces.size) { pieces[it].start }
        val to = IntArray(pieces.size) { pieces[it].end }
        return BertSentence(text, ids.toSeries(), ids.size j { i: Int -> from[i] j to[i] })
    }

    fun encode(token: BertToken, encoded: BertSentence = sentence(token.sentence)): BertInput {
        require(token.lemma.isNotBlank() && token.lemma.length <= 128) { "Invalid BERT target lemma" }
        require(encoded.text == token.sentence) { "BERT target and tokenized sentence differ" }
        val (bounds, method) = target(token)
        if (bounds == null) return BertInput(token, method, encoded.ids, encoded.offsets, emptySeriesOf())
        val hits = IntArray(encoded.ids.size)
        var count = 0
        for (i in 0 until encoded.offsets.size) {
            val offset = encoded.offsets[i]
            if (offset.b > bounds.a && offset.a < bounds.b && offset.b > offset.a) hits[count++] = i
        }
        val complete = count > 0 && encoded.offsets[hits[0]].a <= bounds.a &&
            encoded.offsets[hits[count - 1]].b >= bounds.b
        return BertInput(token, if (complete) method else BertSpan.Truncated, encoded.ids, encoded.offsets,
            if (complete) count j { i: Int -> hits[i] } else emptySeriesOf())
    }

    fun target(token: BertToken): Join<Twin<Int>?, BertSpan> {
        require(token.lemma.isNotBlank() && token.lemma.length <= 128 && token.sentence.length <= 16_384) {
            "Invalid BERT target"
        }
        require((token.start == null) == (token.end == null)) { "BERT target offsets must be paired" }
        val start = token.start
        if (start != null) {
            val end = requireNotNull(token.end)
            require(start >= 0 && end > start && end <= token.sentence.length &&
                scalarBoundary(token.sentence, start) && scalarBoundary(token.sentence, end)) { "Invalid BERT target offsets" }
            return (start j end) j BertSpan.Explicit
        }
        val text = token.sentence
        var first: Twin<Int>? = null
        var count = 0
        var at = 0
        while (at <= text.length - token.lemma.length) {
            val end = at + token.lemma.length
            if (text.regionMatches(at, token.lemma, 0, token.lemma.length, ignoreCase = true) &&
                (at == 0 || !asciiLetterFold(text[at - 1])) &&
                (end == text.length || !asciiLetterFold(text[end]))) {
                first = at j end
                count++
                at = end
            } else at++
        }
        if (count > 0) return (if (count == 1) first else null) j
            (if (count == 1) BertSpan.Exact else BertSpan.Ambiguous)
        val lemmaLength = scalarBounds(token.lemma).size - 1
        val lower = token.lemma.lowercase()
        val lowerBounds = scalarBounds(lower)
        val stem = lower.substring(0, lowerBounds[minOf(maxOf(4, lemmaLength - 2), lowerBounds.size - 1)])
        at = 0
        while (at < text.length) {
            if (!asciiLetter(text[at])) { at++; continue }
            val start = at
            while (at < text.length && asciiLetter(text[at])) at++
            if (text.substring(start, at).lowercase().startsWith(stem)) {
                first = start j at
                count++
            }
        }
        return (if (count == 1) first else null) j when {
            count == 1 -> BertSpan.Stem
            count > 1 -> BertSpan.Ambiguous
            else -> BertSpan.Missing
        }
    }

    data class Piece(val id: Int, val start: Int, val end: Int)

    companion object {
        fun parseVocabulary(text: String): Series<String> {
            val limit = if (text.endsWith('\n')) text.length - 1 else text.length
            val starts = ArrayList<Int>()
            starts.add(0)
            for (i in 0 until limit) if (text[i] == '\n') starts.add(i + 1)
            return starts.size j { i: Int ->
                val end = if (i + 1 == starts.size) limit else starts[i + 1] - 1
                text.substring(starts[i], end).removeSuffix("\r")
            }
        }
        fun asciiLetter(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z'
        fun asciiLetterFold(c: Char): Boolean = asciiLetter(c) || c == 'İ' || c == 'ı' || c == 'ſ' || c == 'K'
        fun scalarBoundary(text: String, at: Int): Boolean =
            at == 0 || at == text.length || !(text[at].isLowSurrogate() && text[at - 1].isHighSurrogate())
        fun scalar(text: String, at: Int): Int {
            val c = text[at]
            if (c.isHighSurrogate()) {
                require(at + 1 < text.length && text[at + 1].isLowSurrogate()) { "Invalid UTF-16 BERT sentence" }
                return 0x10000 + ((c.code - 0xd800) shl 10) + text[at + 1].code - 0xdc00
            }
            require(!c.isLowSurrogate()) { "Invalid UTF-16 BERT sentence" }
            return c.code
        }
        fun scalarWidth(cp: Int): Int = if (cp >= 0x10000) 2 else 1
        fun scalarString(cp: Int): String = if (cp < 0x10000) cp.toChar().toString()
            else charArrayOf((0xd800 + ((cp - 0x10000) ushr 10)).toChar(), (0xdc00 + ((cp - 0x10000) and 1023)).toChar()).concatToString()
        fun scalarBounds(text: String): IntArray {
            val bounds = IntArray(text.length + 1)
            var count = 0
            var at = 0
            while (at < text.length) { bounds[count++] = at; at += scalarWidth(scalar(text, at)) }
            bounds[count] = at
            return bounds.copyOf(count + 1)
        }
        fun space(cp: Int): Boolean = BertUnicode.contains(BertUnicode.spaces, cp)
        fun punctuation(cp: Int): Boolean = cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126 ||
            BertUnicode.contains(BertUnicode.punctuation, cp)
        fun chinese(cp: Int): Boolean = cp in 0x4e00..0x9fff || cp in 0x3400..0x4dbf ||
            cp in 0x20000..0x2a6df || cp in 0x2a700..0x2b73f || cp in 0x2b740..0x2b81f ||
            cp in 0x2b820..0x2ceaf || cp in 0xf900..0xfaff || cp in 0x2f800..0x2fa1f
        fun normalize(cp: Int): String {
            if (BertUnicode.contains(BertUnicode.removed, cp)) return ""
            if (cp in 0xac00..0xd7a3) {
                val syllable = cp - 0xac00
                val tail = syllable % 28
                return scalarString(0x1100 + syllable / 588) + scalarString(0x1161 + syllable % 588 / 28) +
                    if (tail == 0) "" else scalarString(0x11a7 + tail)
            }
            return BertUnicode.transforms[cp] ?: scalarString(cp)
        }
    }
}

/** Scalar tables recorded from tokenizers 0.23.2's cached bert-base-uncased normalizer and pre-tokenizer. */
object BertUnicode {
    fun ranges(text: String): IntArray = text.split(',').map { it.toInt(16) }.toIntArray()
    fun contains(ranges: IntArray, cp: Int): Boolean {
        var low = 0
        var high = ranges.size / 2 - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (cp < ranges[mid * 2]) high = mid - 1
            else if (cp > ranges[mid * 2 + 1]) low = mid + 1
            else return true
        }
        return false
    }
    val transforms: Map<Int, String> = TRANSFORMS.split(';').associate { row ->
        val fields = row.split('=')
        fields[0].toInt(16) to fields[1].split(',').joinToString("") { BertWordPiece.scalarString(it.toInt(16)) }
    }
    val removed = ranges(REMOVED)
    val punctuation = ranges(PUNCTUATION)
    val spaces = ranges(SPACES)
    const val TRANSFORMS =
        "41=61;42=62;43=63;44=64;45=65;46=66;47=67;48=68;49=69;4a=6a;4b=6b;4c=6c;4d=6d;4e=6e;4f=6f;50=70;51=7" +
        "1;52=72;53=73;54=74;55=75;56=76;57=77;58=78;59=79;5a=7a;c0=61;c1=61;c2=61;c3=61;c4=61;c5=61;c6=e6;c7" +
        "=63;c8=65;c9=65;ca=65;cb=65;cc=69;cd=69;ce=69;cf=69;d0=f0;d1=6e;d2=6f;d3=6f;d4=6f;d5=6f;d6=6f;d8=f8;" +
        "d9=75;da=75;db=75;dc=75;dd=79;de=fe;e0=61;e1=61;e2=61;e3=61;e4=61;e5=61;e7=63;e8=65;e9=65;ea=65;eb=6" +
        "5;ec=69;ed=69;ee=69;ef=69;f1=6e;f2=6f;f3=6f;f4=6f;f5=6f;f6=6f;f9=75;fa=75;fb=75;fc=75;fd=79;ff=79;10" +
        "0=61;101=61;102=61;103=61;104=61;105=61;106=63;107=63;108=63;109=63;10a=63;10b=63;10c=63;10d=63;10e=" +
        "64;10f=64;110=111;112=65;113=65;114=65;115=65;116=65;117=65;118=65;119=65;11a=65;11b=65;11c=67;11d=6" +
        "7;11e=67;11f=67;120=67;121=67;122=67;123=67;124=68;125=68;126=127;128=69;129=69;12a=69;12b=69;12c=69" +
        ";12d=69;12e=69;12f=69;130=69;132=133;134=6a;135=6a;136=6b;137=6b;139=6c;13a=6c;13b=6c;13c=6c;13d=6c;" +
        "13e=6c;13f=140;141=142;143=6e;144=6e;145=6e;146=6e;147=6e;148=6e;14a=14b;14c=6f;14d=6f;14e=6f;14f=6f" +
        ";150=6f;151=6f;152=153;154=72;155=72;156=72;157=72;158=72;159=72;15a=73;15b=73;15c=73;15d=73;15e=73;" +
        "15f=73;160=73;161=73;162=74;163=74;164=74;165=74;166=167;168=75;169=75;16a=75;16b=75;16c=75;16d=75;1" +
        "6e=75;16f=75;170=75;171=75;172=75;173=75;174=77;175=77;176=79;177=79;178=79;179=7a;17a=7a;17b=7a;17c" +
        "=7a;17d=7a;17e=7a;181=253;182=183;184=185;186=254;187=188;189=256;18a=257;18b=18c;18e=1dd;18f=259;19" +
        "0=25b;191=192;193=260;194=263;196=269;197=268;198=199;19c=26f;19d=272;19f=275;1a0=6f;1a1=6f;1a2=1a3;" +
        "1a4=1a5;1a6=280;1a7=1a8;1a9=283;1ac=1ad;1ae=288;1af=75;1b0=75;1b1=28a;1b2=28b;1b3=1b4;1b5=1b6;1b7=29" +
        "2;1b8=1b9;1bc=1bd;1c4=1c6;1c5=1c6;1c7=1c9;1c8=1c9;1ca=1cc;1cb=1cc;1cd=61;1ce=61;1cf=69;1d0=69;1d1=6f" +
        ";1d2=6f;1d3=75;1d4=75;1d5=75;1d6=75;1d7=75;1d8=75;1d9=75;1da=75;1db=75;1dc=75;1de=61;1df=61;1e0=61;1" +
        "e1=61;1e2=e6;1e3=e6;1e4=1e5;1e6=67;1e7=67;1e8=6b;1e9=6b;1ea=6f;1eb=6f;1ec=6f;1ed=6f;1ee=292;1ef=292;" +
        "1f0=6a;1f1=1f3;1f2=1f3;1f4=67;1f5=67;1f6=195;1f7=1bf;1f8=6e;1f9=6e;1fa=61;1fb=61;1fc=e6;1fd=e6;1fe=f" +
        "8;1ff=f8;200=61;201=61;202=61;203=61;204=65;205=65;206=65;207=65;208=69;209=69;20a=69;20b=69;20c=6f;" +
        "20d=6f;20e=6f;20f=6f;210=72;211=72;212=72;213=72;214=75;215=75;216=75;217=75;218=73;219=73;21a=74;21" +
        "b=74;21c=21d;21e=68;21f=68;220=19e;222=223;224=225;226=61;227=61;228=65;229=65;22a=6f;22b=6f;22c=6f;" +
        "22d=6f;22e=6f;22f=6f;230=6f;231=6f;232=79;233=79;23a=2c65;23b=23c;23d=19a;23e=2c66;241=242;243=180;2" +
        "44=289;245=28c;246=247;248=249;24a=24b;24c=24d;24e=24f;370=371;372=373;374=2b9;376=377;37e=3b;37f=3f" +
        "3;385=a8;386=3b1;387=b7;388=3b5;389=3b7;38a=3b9;38c=3bf;38e=3c5;38f=3c9;390=3b9;391=3b1;392=3b2;393=" +
        "3b3;394=3b4;395=3b5;396=3b6;397=3b7;398=3b8;399=3b9;39a=3ba;39b=3bb;39c=3bc;39d=3bd;39e=3be;39f=3bf;" +
        "3a0=3c0;3a1=3c1;3a3=3c3;3a4=3c4;3a5=3c5;3a6=3c6;3a7=3c7;3a8=3c8;3a9=3c9;3aa=3b9;3ab=3c5;3ac=3b1;3ad=" +
        "3b5;3ae=3b7;3af=3b9;3b0=3c5;3ca=3b9;3cb=3c5;3cc=3bf;3cd=3c5;3ce=3c9;3cf=3d7;3d3=3d2;3d4=3d2;3d8=3d9;" +
        "3da=3db;3dc=3dd;3de=3df;3e0=3e1;3e2=3e3;3e4=3e5;3e6=3e7;3e8=3e9;3ea=3eb;3ec=3ed;3ee=3ef;3f4=3b8;3f7=" +
        "3f8;3f9=3f2;3fa=3fb;3fd=37b;3fe=37c;3ff=37d;400=435;401=435;402=452;403=433;404=454;405=455;406=456;" +
        "407=456;408=458;409=459;40a=45a;40b=45b;40c=43a;40d=438;40e=443;40f=45f;410=430;411=431;412=432;413=" +
        "433;414=434;415=435;416=436;417=437;418=438;419=438;41a=43a;41b=43b;41c=43c;41d=43d;41e=43e;41f=43f;" +
        "420=440;421=441;422=442;423=443;424=444;425=445;426=446;427=447;428=448;429=449;42a=44a;42b=44b;42c=" +
        "44c;42d=44d;42e=44e;42f=44f;439=438;450=435;451=435;453=433;457=456;45c=43a;45d=438;45e=443;460=461;" +
        "462=463;464=465;466=467;468=469;46a=46b;46c=46d;46e=46f;470=471;472=473;474=475;476=475;477=475;478=" +
        "479;47a=47b;47c=47d;47e=47f;480=481;48a=48b;48c=48d;48e=48f;490=491;492=493;494=495;496=497;498=499;" +
        "49a=49b;49c=49d;49e=49f;4a0=4a1;4a2=4a3;4a4=4a5;4a6=4a7;4a8=4a9;4aa=4ab;4ac=4ad;4ae=4af;4b0=4b1;4b2=" +
        "4b3;4b4=4b5;4b6=4b7;4b8=4b9;4ba=4bb;4bc=4bd;4be=4bf;4c0=4cf;4c1=436;4c2=436;4c3=4c4;4c5=4c6;4c7=4c8;" +
        "4c9=4ca;4cb=4cc;4cd=4ce;4d0=430;4d1=430;4d2=430;4d3=430;4d4=4d5;4d6=435;4d7=435;4d8=4d9;4da=4d9;4db=" +
        "4d9;4dc=436;4dd=436;4de=437;4df=437;4e0=4e1;4e2=438;4e3=438;4e4=438;4e5=438;4e6=43e;4e7=43e;4e8=4e9;" +
        "4ea=4e9;4eb=4e9;4ec=44d;4ed=44d;4ee=443;4ef=443;4f0=443;4f1=443;4f2=443;4f3=443;4f4=447;4f5=447;4f6=" +
        "4f7;4f8=44b;4f9=44b;4fa=4fb;4fc=4fd;4fe=4ff;500=501;502=503;504=505;506=507;508=509;50a=50b;50c=50d;" +
        "50e=50f;510=511;512=513;514=515;516=517;518=519;51a=51b;51c=51d;51e=51f;520=521;522=523;524=525;526=" +
        "527;528=529;52a=52b;52c=52d;52e=52f;531=561;532=562;533=563;534=564;535=565;536=566;537=567;538=568;" +
        "539=569;53a=56a;53b=56b;53c=56c;53d=56d;53e=56e;53f=56f;540=570;541=571;542=572;543=573;544=574;545=" +
        "575;546=576;547=577;548=578;549=579;54a=57a;54b=57b;54c=57c;54d=57d;54e=57e;54f=57f;550=580;551=581;" +
        "552=582;553=583;554=584;555=585;556=586;622=627;623=627;624=648;625=627;626=64a;6c0=6d5;6c2=6c1;6d3=" +
        "6d2;929=928;931=930;934=933;958=915;959=916;95a=917;95b=91c;95c=921;95d=922;95e=92b;95f=92f;9cb=9c7," +
        "9be;9cc=9c7,9d7;9dc=9a1;9dd=9a2;9df=9af;a33=a32;a36=a38;a59=a16;a5a=a17;a5b=a1c;a5e=a2b;b48=b47;b4b=" +
        "b47,b3e;b4c=b47,b57;b5c=b21;b5d=b22;b94=b92,bd7;bca=bc6,bbe;bcb=bc7,bbe;bcc=bc6,bd7;cc0=cd5;cc7=cd5;" +
        "cc8=cd6;cca=cc2;ccb=cc2,cd5;d4a=d46,d3e;d4b=d47,d3e;d4c=d46,d57;dda=dd9;ddc=dd9,dcf;ddd=dd9,dcf;dde=" +
        "dd9,ddf;f43=f42;f4d=f4c;f52=f51;f57=f56;f5c=f5b;f69=f40;1026=1025;10a0=2d00;10a1=2d01;10a2=2d02;10a3" +
        "=2d03;10a4=2d04;10a5=2d05;10a6=2d06;10a7=2d07;10a8=2d08;10a9=2d09;10aa=2d0a;10ab=2d0b;10ac=2d0c;10ad" +
        "=2d0d;10ae=2d0e;10af=2d0f;10b0=2d10;10b1=2d11;10b2=2d12;10b3=2d13;10b4=2d14;10b5=2d15;10b6=2d16;10b7" +
        "=2d17;10b8=2d18;10b9=2d19;10ba=2d1a;10bb=2d1b;10bc=2d1c;10bd=2d1d;10be=2d1e;10bf=2d1f;10c0=2d20;10c1" +
        "=2d21;10c2=2d22;10c3=2d23;10c4=2d24;10c5=2d25;10c7=2d27;10cd=2d2d;13a0=ab70;13a1=ab71;13a2=ab72;13a3" +
        "=ab73;13a4=ab74;13a5=ab75;13a6=ab76;13a7=ab77;13a8=ab78;13a9=ab79;13aa=ab7a;13ab=ab7b;13ac=ab7c;13ad" +
        "=ab7d;13ae=ab7e;13af=ab7f;13b0=ab80;13b1=ab81;13b2=ab82;13b3=ab83;13b4=ab84;13b5=ab85;13b6=ab86;13b7" +
        "=ab87;13b8=ab88;13b9=ab89;13ba=ab8a;13bb=ab8b;13bc=ab8c;13bd=ab8d;13be=ab8e;13bf=ab8f;13c0=ab90;13c1" +
        "=ab91;13c2=ab92;13c3=ab93;13c4=ab94;13c5=ab95;13c6=ab96;13c7=ab97;13c8=ab98;13c9=ab99;13ca=ab9a;13cb" +
        "=ab9b;13cc=ab9c;13cd=ab9d;13ce=ab9e;13cf=ab9f;13d0=aba0;13d1=aba1;13d2=aba2;13d3=aba3;13d4=aba4;13d5" +
        "=aba5;13d6=aba6;13d7=aba7;13d8=aba8;13d9=aba9;13da=abaa;13db=abab;13dc=abac;13dd=abad;13de=abae;13df" +
        "=abaf;13e0=abb0;13e1=abb1;13e2=abb2;13e3=abb3;13e4=abb4;13e5=abb5;13e6=abb6;13e7=abb7;13e8=abb8;13e9" +
        "=abb9;13ea=abba;13eb=abbb;13ec=abbc;13ed=abbd;13ee=abbe;13ef=abbf;13f0=13f8;13f1=13f9;13f2=13fa;13f3" +
        "=13fb;13f4=13fc;13f5=13fd;1b06=1b05,1b35;1b08=1b07,1b35;1b0a=1b09,1b35;1b0c=1b0b,1b35;1b0e=1b0d,1b35" +
        ";1b12=1b11,1b35;1b3b=1b35;1b3d=1b35;1b40=1b3e,1b35;1b41=1b3f,1b35;1b43=1b35;1c89=1c8a;1c90=10d0;1c91" +
        "=10d1;1c92=10d2;1c93=10d3;1c94=10d4;1c95=10d5;1c96=10d6;1c97=10d7;1c98=10d8;1c99=10d9;1c9a=10da;1c9b" +
        "=10db;1c9c=10dc;1c9d=10dd;1c9e=10de;1c9f=10df;1ca0=10e0;1ca1=10e1;1ca2=10e2;1ca3=10e3;1ca4=10e4;1ca5" +
        "=10e5;1ca6=10e6;1ca7=10e7;1ca8=10e8;1ca9=10e9;1caa=10ea;1cab=10eb;1cac=10ec;1cad=10ed;1cae=10ee;1caf" +
        "=10ef;1cb0=10f0;1cb1=10f1;1cb2=10f2;1cb3=10f3;1cb4=10f4;1cb5=10f5;1cb6=10f6;1cb7=10f7;1cb8=10f8;1cb9" +
        "=10f9;1cba=10fa;1cbd=10fd;1cbe=10fe;1cbf=10ff;1e00=61;1e01=61;1e02=62;1e03=62;1e04=62;1e05=62;1e06=6" +
        "2;1e07=62;1e08=63;1e09=63;1e0a=64;1e0b=64;1e0c=64;1e0d=64;1e0e=64;1e0f=64;1e10=64;1e11=64;1e12=64;1e" +
        "13=64;1e14=65;1e15=65;1e16=65;1e17=65;1e18=65;1e19=65;1e1a=65;1e1b=65;1e1c=65;1e1d=65;1e1e=66;1e1f=6" +
        "6;1e20=67;1e21=67;1e22=68;1e23=68;1e24=68;1e25=68;1e26=68;1e27=68;1e28=68;1e29=68;1e2a=68;1e2b=68;1e" +
        "2c=69;1e2d=69;1e2e=69;1e2f=69;1e30=6b;1e31=6b;1e32=6b;1e33=6b;1e34=6b;1e35=6b;1e36=6c;1e37=6c;1e38=6" +
        "c;1e39=6c;1e3a=6c;1e3b=6c;1e3c=6c;1e3d=6c;1e3e=6d;1e3f=6d;1e40=6d;1e41=6d;1e42=6d;1e43=6d;1e44=6e;1e" +
        "45=6e;1e46=6e;1e47=6e;1e48=6e;1e49=6e;1e4a=6e;1e4b=6e;1e4c=6f;1e4d=6f;1e4e=6f;1e4f=6f;1e50=6f;1e51=6" +
        "f;1e52=6f;1e53=6f;1e54=70;1e55=70;1e56=70;1e57=70;1e58=72;1e59=72;1e5a=72;1e5b=72;1e5c=72;1e5d=72;1e" +
        "5e=72;1e5f=72;1e60=73;1e61=73;1e62=73;1e63=73;1e64=73;1e65=73;1e66=73;1e67=73;1e68=73;1e69=73;1e6a=7" +
        "4;1e6b=74;1e6c=74;1e6d=74;1e6e=74;1e6f=74;1e70=74;1e71=74;1e72=75;1e73=75;1e74=75;1e75=75;1e76=75;1e" +
        "77=75;1e78=75;1e79=75;1e7a=75;1e7b=75;1e7c=76;1e7d=76;1e7e=76;1e7f=76;1e80=77;1e81=77;1e82=77;1e83=7" +
        "7;1e84=77;1e85=77;1e86=77;1e87=77;1e88=77;1e89=77;1e8a=78;1e8b=78;1e8c=78;1e8d=78;1e8e=79;1e8f=79;1e" +
        "90=7a;1e91=7a;1e92=7a;1e93=7a;1e94=7a;1e95=7a;1e96=68;1e97=74;1e98=77;1e99=79;1e9b=17f;1e9e=df;1ea0=" +
        "61;1ea1=61;1ea2=61;1ea3=61;1ea4=61;1ea5=61;1ea6=61;1ea7=61;1ea8=61;1ea9=61;1eaa=61;1eab=61;1eac=61;1" +
        "ead=61;1eae=61;1eaf=61;1eb0=61;1eb1=61;1eb2=61;1eb3=61;1eb4=61;1eb5=61;1eb6=61;1eb7=61;1eb8=65;1eb9=" +
        "65;1eba=65;1ebb=65;1ebc=65;1ebd=65;1ebe=65;1ebf=65;1ec0=65;1ec1=65;1ec2=65;1ec3=65;1ec4=65;1ec5=65;1" +
        "ec6=65;1ec7=65;1ec8=69;1ec9=69;1eca=69;1ecb=69;1ecc=6f;1ecd=6f;1ece=6f;1ecf=6f;1ed0=6f;1ed1=6f;1ed2=" +
        "6f;1ed3=6f;1ed4=6f;1ed5=6f;1ed6=6f;1ed7=6f;1ed8=6f;1ed9=6f;1eda=6f;1edb=6f;1edc=6f;1edd=6f;1ede=6f;1" +
        "edf=6f;1ee0=6f;1ee1=6f;1ee2=6f;1ee3=6f;1ee4=75;1ee5=75;1ee6=75;1ee7=75;1ee8=75;1ee9=75;1eea=75;1eeb=" +
        "75;1eec=75;1eed=75;1eee=75;1eef=75;1ef0=75;1ef1=75;1ef2=79;1ef3=79;1ef4=79;1ef5=79;1ef6=79;1ef7=79;1" +
        "ef8=79;1ef9=79;1efa=1efb;1efc=1efd;1efe=1eff;1f00=3b1;1f01=3b1;1f02=3b1;1f03=3b1;1f04=3b1;1f05=3b1;1" +
        "f06=3b1;1f07=3b1;1f08=3b1;1f09=3b1;1f0a=3b1;1f0b=3b1;1f0c=3b1;1f0d=3b1;1f0e=3b1;1f0f=3b1;1f10=3b5;1f" +
        "11=3b5;1f12=3b5;1f13=3b5;1f14=3b5;1f15=3b5;1f18=3b5;1f19=3b5;1f1a=3b5;1f1b=3b5;1f1c=3b5;1f1d=3b5;1f2" +
        "0=3b7;1f21=3b7;1f22=3b7;1f23=3b7;1f24=3b7;1f25=3b7;1f26=3b7;1f27=3b7;1f28=3b7;1f29=3b7;1f2a=3b7;1f2b" +
        "=3b7;1f2c=3b7;1f2d=3b7;1f2e=3b7;1f2f=3b7;1f30=3b9;1f31=3b9;1f32=3b9;1f33=3b9;1f34=3b9;1f35=3b9;1f36=" +
        "3b9;1f37=3b9;1f38=3b9;1f39=3b9;1f3a=3b9;1f3b=3b9;1f3c=3b9;1f3d=3b9;1f3e=3b9;1f3f=3b9;1f40=3bf;1f41=3" +
        "bf;1f42=3bf;1f43=3bf;1f44=3bf;1f45=3bf;1f48=3bf;1f49=3bf;1f4a=3bf;1f4b=3bf;1f4c=3bf;1f4d=3bf;1f50=3c" +
        "5;1f51=3c5;1f52=3c5;1f53=3c5;1f54=3c5;1f55=3c5;1f56=3c5;1f57=3c5;1f59=3c5;1f5b=3c5;1f5d=3c5;1f5f=3c5" +
        ";1f60=3c9;1f61=3c9;1f62=3c9;1f63=3c9;1f64=3c9;1f65=3c9;1f66=3c9;1f67=3c9;1f68=3c9;1f69=3c9;1f6a=3c9;" +
        "1f6b=3c9;1f6c=3c9;1f6d=3c9;1f6e=3c9;1f6f=3c9;1f70=3b1;1f71=3b1;1f72=3b5;1f73=3b5;1f74=3b7;1f75=3b7;1" +
        "f76=3b9;1f77=3b9;1f78=3bf;1f79=3bf;1f7a=3c5;1f7b=3c5;1f7c=3c9;1f7d=3c9;1f80=3b1;1f81=3b1;1f82=3b1;1f" +
        "83=3b1;1f84=3b1;1f85=3b1;1f86=3b1;1f87=3b1;1f88=3b1;1f89=3b1;1f8a=3b1;1f8b=3b1;1f8c=3b1;1f8d=3b1;1f8" +
        "e=3b1;1f8f=3b1;1f90=3b7;1f91=3b7;1f92=3b7;1f93=3b7;1f94=3b7;1f95=3b7;1f96=3b7;1f97=3b7;1f98=3b7;1f99" +
        "=3b7;1f9a=3b7;1f9b=3b7;1f9c=3b7;1f9d=3b7;1f9e=3b7;1f9f=3b7;1fa0=3c9;1fa1=3c9;1fa2=3c9;1fa3=3c9;1fa4=" +
        "3c9;1fa5=3c9;1fa6=3c9;1fa7=3c9;1fa8=3c9;1fa9=3c9;1faa=3c9;1fab=3c9;1fac=3c9;1fad=3c9;1fae=3c9;1faf=3" +
        "c9;1fb0=3b1;1fb1=3b1;1fb2=3b1;1fb3=3b1;1fb4=3b1;1fb6=3b1;1fb7=3b1;1fb8=3b1;1fb9=3b1;1fba=3b1;1fbb=3b" +
        "1;1fbc=3b1;1fbe=3b9;1fc1=a8;1fc2=3b7;1fc3=3b7;1fc4=3b7;1fc6=3b7;1fc7=3b7;1fc8=3b5;1fc9=3b5;1fca=3b7;" +
        "1fcb=3b7;1fcc=3b7;1fcd=1fbf;1fce=1fbf;1fcf=1fbf;1fd0=3b9;1fd1=3b9;1fd2=3b9;1fd3=3b9;1fd6=3b9;1fd7=3b" +
        "9;1fd8=3b9;1fd9=3b9;1fda=3b9;1fdb=3b9;1fdd=1ffe;1fde=1ffe;1fdf=1ffe;1fe0=3c5;1fe1=3c5;1fe2=3c5;1fe3=" +
        "3c5;1fe4=3c1;1fe5=3c1;1fe6=3c5;1fe7=3c5;1fe8=3c5;1fe9=3c5;1fea=3c5;1feb=3c5;1fec=3c1;1fed=a8;1fee=a8" +
        ";1fef=60;1ff2=3c9;1ff3=3c9;1ff4=3c9;1ff6=3c9;1ff7=3c9;1ff8=3bf;1ff9=3bf;1ffa=3c9;1ffb=3c9;1ffc=3c9;1" +
        "ffd=b4;2126=3c9;212a=6b;212b=61;2132=214e;2160=2170;2161=2171;2162=2172;2163=2173;2164=2174;2165=217" +
        "5;2166=2176;2167=2177;2168=2178;2169=2179;216a=217a;216b=217b;216c=217c;216d=217d;216e=217e;216f=217" +
        "f;2183=2184;219a=2190;219b=2192;21ae=2194;21cd=21d0;21ce=21d4;21cf=21d2;2204=2203;2209=2208;220c=220" +
        "b;2224=2223;2226=2225;2241=223c;2244=2243;2247=2245;2249=2248;2260=3d;2262=2261;226d=224d;226e=3c;22" +
        "6f=3e;2270=2264;2271=2265;2274=2272;2275=2273;2278=2276;2279=2277;2280=227a;2281=227b;2284=2282;2285" +
        "=2283;2288=2286;2289=2287;22ac=22a2;22ad=22a8;22ae=22a9;22af=22ab;22e0=227c;22e1=227d;22e2=2291;22e3" +
        "=2292;22ea=22b2;22eb=22b3;22ec=22b4;22ed=22b5;2329=3008;232a=3009;24b6=24d0;24b7=24d1;24b8=24d2;24b9" +
        "=24d3;24ba=24d4;24bb=24d5;24bc=24d6;24bd=24d7;24be=24d8;24bf=24d9;24c0=24da;24c1=24db;24c2=24dc;24c3" +
        "=24dd;24c4=24de;24c5=24df;24c6=24e0;24c7=24e1;24c8=24e2;24c9=24e3;24ca=24e4;24cb=24e5;24cc=24e6;24cd" +
        "=24e7;24ce=24e8;24cf=24e9;2adc=2add;2c00=2c30;2c01=2c31;2c02=2c32;2c03=2c33;2c04=2c34;2c05=2c35;2c06" +
        "=2c36;2c07=2c37;2c08=2c38;2c09=2c39;2c0a=2c3a;2c0b=2c3b;2c0c=2c3c;2c0d=2c3d;2c0e=2c3e;2c0f=2c3f;2c10" +
        "=2c40;2c11=2c41;2c12=2c42;2c13=2c43;2c14=2c44;2c15=2c45;2c16=2c46;2c17=2c47;2c18=2c48;2c19=2c49;2c1a" +
        "=2c4a;2c1b=2c4b;2c1c=2c4c;2c1d=2c4d;2c1e=2c4e;2c1f=2c4f;2c20=2c50;2c21=2c51;2c22=2c52;2c23=2c53;2c24" +
        "=2c54;2c25=2c55;2c26=2c56;2c27=2c57;2c28=2c58;2c29=2c59;2c2a=2c5a;2c2b=2c5b;2c2c=2c5c;2c2d=2c5d;2c2e" +
        "=2c5e;2c2f=2c5f;2c60=2c61;2c62=26b;2c63=1d7d;2c64=27d;2c67=2c68;2c69=2c6a;2c6b=2c6c;2c6d=251;2c6e=27" +
        "1;2c6f=250;2c70=252;2c72=2c73;2c75=2c76;2c7e=23f;2c7f=240;2c80=2c81;2c82=2c83;2c84=2c85;2c86=2c87;2c" +
        "88=2c89;2c8a=2c8b;2c8c=2c8d;2c8e=2c8f;2c90=2c91;2c92=2c93;2c94=2c95;2c96=2c97;2c98=2c99;2c9a=2c9b;2c" +
        "9c=2c9d;2c9e=2c9f;2ca0=2ca1;2ca2=2ca3;2ca4=2ca5;2ca6=2ca7;2ca8=2ca9;2caa=2cab;2cac=2cad;2cae=2caf;2c" +
        "b0=2cb1;2cb2=2cb3;2cb4=2cb5;2cb6=2cb7;2cb8=2cb9;2cba=2cbb;2cbc=2cbd;2cbe=2cbf;2cc0=2cc1;2cc2=2cc3;2c" +
        "c4=2cc5;2cc6=2cc7;2cc8=2cc9;2cca=2ccb;2ccc=2ccd;2cce=2ccf;2cd0=2cd1;2cd2=2cd3;2cd4=2cd5;2cd6=2cd7;2c" +
        "d8=2cd9;2cda=2cdb;2cdc=2cdd;2cde=2cdf;2ce0=2ce1;2ce2=2ce3;2ceb=2cec;2ced=2cee;2cf2=2cf3;304c=304b;30" +
        "4e=304d;3050=304f;3052=3051;3054=3053;3056=3055;3058=3057;305a=3059;305c=305b;305e=305d;3060=305f;30" +
        "62=3061;3065=3064;3067=3066;3069=3068;3070=306f;3071=306f;3073=3072;3074=3072;3076=3075;3077=3075;30" +
        "79=3078;307a=3078;307c=307b;307d=307b;3094=3046;309e=309d;30ac=30ab;30ae=30ad;30b0=30af;30b2=30b1;30" +
        "b4=30b3;30b6=30b5;30b8=30b7;30ba=30b9;30bc=30bb;30be=30bd;30c0=30bf;30c2=30c1;30c5=30c4;30c7=30c6;30" +
        "c9=30c8;30d0=30cf;30d1=30cf;30d3=30d2;30d4=30d2;30d6=30d5;30d7=30d5;30d9=30d8;30da=30d8;30dc=30db;30" +
        "dd=30db;30f4=30a6;30f7=30ef;30f8=30f0;30f9=30f1;30fa=30f2;30fe=30fd;a640=a641;a642=a643;a644=a645;a6" +
        "46=a647;a648=a649;a64a=a64b;a64c=a64d;a64e=a64f;a650=a651;a652=a653;a654=a655;a656=a657;a658=a659;a6" +
        "5a=a65b;a65c=a65d;a65e=a65f;a660=a661;a662=a663;a664=a665;a666=a667;a668=a669;a66a=a66b;a66c=a66d;a6" +
        "80=a681;a682=a683;a684=a685;a686=a687;a688=a689;a68a=a68b;a68c=a68d;a68e=a68f;a690=a691;a692=a693;a6" +
        "94=a695;a696=a697;a698=a699;a69a=a69b;a722=a723;a724=a725;a726=a727;a728=a729;a72a=a72b;a72c=a72d;a7" +
        "2e=a72f;a732=a733;a734=a735;a736=a737;a738=a739;a73a=a73b;a73c=a73d;a73e=a73f;a740=a741;a742=a743;a7" +
        "44=a745;a746=a747;a748=a749;a74a=a74b;a74c=a74d;a74e=a74f;a750=a751;a752=a753;a754=a755;a756=a757;a7" +
        "58=a759;a75a=a75b;a75c=a75d;a75e=a75f;a760=a761;a762=a763;a764=a765;a766=a767;a768=a769;a76a=a76b;a7" +
        "6c=a76d;a76e=a76f;a779=a77a;a77b=a77c;a77d=1d79;a77e=a77f;a780=a781;a782=a783;a784=a785;a786=a787;a7" +
        "8b=a78c;a78d=265;a790=a791;a792=a793;a796=a797;a798=a799;a79a=a79b;a79c=a79d;a79e=a79f;a7a0=a7a1;a7a" +
        "2=a7a3;a7a4=a7a5;a7a6=a7a7;a7a8=a7a9;a7aa=266;a7ab=25c;a7ac=261;a7ad=26c;a7ae=26a;a7b0=29e;a7b1=287;" +
        "a7b2=29d;a7b3=ab53;a7b4=a7b5;a7b6=a7b7;a7b8=a7b9;a7ba=a7bb;a7bc=a7bd;a7be=a7bf;a7c0=a7c1;a7c2=a7c3;a" +
        "7c4=a794;a7c5=282;a7c6=1d8e;a7c7=a7c8;a7c9=a7ca;a7cb=264;a7cc=a7cd;a7ce=a7cf;a7d0=a7d1;a7d2=a7d3;a7d" +
        "4=a7d5;a7d6=a7d7;a7d8=a7d9;a7da=a7db;a7dc=19b;a7f5=a7f6;f900=8c48;f901=66f4;f902=8eca;f903=8cc8;f904" +
        "=6ed1;f905=4e32;f906=53e5;f907=9f9c;f908=9f9c;f909=5951;f90a=91d1;f90b=5587;f90c=5948;f90d=61f6;f90e" +
        "=7669;f90f=7f85;f910=863f;f911=87ba;f912=88f8;f913=908f;f914=6a02;f915=6d1b;f916=70d9;f917=73de;f918" +
        "=843d;f919=916a;f91a=99f1;f91b=4e82;f91c=5375;f91d=6b04;f91e=721b;f91f=862d;f920=9e1e;f921=5d50;f922" +
        "=6feb;f923=85cd;f924=8964;f925=62c9;f926=81d8;f927=881f;f928=5eca;f929=6717;f92a=6d6a;f92b=72fc;f92c" +
        "=90ce;f92d=4f86;f92e=51b7;f92f=52de;f930=64c4;f931=6ad3;f932=7210;f933=76e7;f934=8001;f935=8606;f936" +
        "=865c;f937=8def;f938=9732;f939=9b6f;f93a=9dfa;f93b=788c;f93c=797f;f93d=7da0;f93e=83c9;f93f=9304;f940" +
        "=9e7f;f941=8ad6;f942=58df;f943=5f04;f944=7c60;f945=807e;f946=7262;f947=78ca;f948=8cc2;f949=96f7;f94a" +
        "=58d8;f94b=5c62;f94c=6a13;f94d=6dda;f94e=6f0f;f94f=7d2f;f950=7e37;f951=964b;f952=52d2;f953=808b;f954" +
        "=51dc;f955=51cc;f956=7a1c;f957=7dbe;f958=83f1;f959=9675;f95a=8b80;f95b=62cf;f95c=6a02;f95d=8afe;f95e" +
        "=4e39;f95f=5be7;f960=6012;f961=7387;f962=7570;f963=5317;f964=78fb;f965=4fbf;f966=5fa9;f967=4e0d;f968" +
        "=6ccc;f969=6578;f96a=7d22;f96b=53c3;f96c=585e;f96d=7701;f96e=8449;f96f=8aaa;f970=6bba;f971=8fb0;f972" +
        "=6c88;f973=62fe;f974=82e5;f975=63a0;f976=7565;f977=4eae;f978=5169;f979=51c9;f97a=6881;f97b=7ce7;f97c" +
        "=826f;f97d=8ad2;f97e=91cf;f97f=52f5;f980=5442;f981=5973;f982=5eec;f983=65c5;f984=6ffe;f985=792a;f986" +
        "=95ad;f987=9a6a;f988=9e97;f989=9ece;f98a=529b;f98b=66c6;f98c=6b77;f98d=8f62;f98e=5e74;f98f=6190;f990" +
        "=6200;f991=649a;f992=6f23;f993=7149;f994=7489;f995=79ca;f996=7df4;f997=806f;f998=8f26;f999=84ee;f99a" +
        "=9023;f99b=934a;f99c=5217;f99d=52a3;f99e=54bd;f99f=70c8;f9a0=88c2;f9a1=8aaa;f9a2=5ec9;f9a3=5ff5;f9a4" +
        "=637b;f9a5=6bae;f9a6=7c3e;f9a7=7375;f9a8=4ee4;f9a9=56f9;f9aa=5be7;f9ab=5dba;f9ac=601c;f9ad=73b2;f9ae" +
        "=7469;f9af=7f9a;f9b0=8046;f9b1=9234;f9b2=96f6;f9b3=9748;f9b4=9818;f9b5=4f8b;f9b6=79ae;f9b7=91b4;f9b8" +
        "=96b8;f9b9=60e1;f9ba=4e86;f9bb=50da;f9bc=5bee;f9bd=5c3f;f9be=6599;f9bf=6a02;f9c0=71ce;f9c1=7642;f9c2" +
        "=84fc;f9c3=907c;f9c4=9f8d;f9c5=6688;f9c6=962e;f9c7=5289;f9c8=677b;f9c9=67f3;f9ca=6d41;f9cb=6e9c;f9cc" +
        "=7409;f9cd=7559;f9ce=786b;f9cf=7d10;f9d0=985e;f9d1=516d;f9d2=622e;f9d3=9678;f9d4=502b;f9d5=5d19;f9d6" +
        "=6dea;f9d7=8f2a;f9d8=5f8b;f9d9=6144;f9da=6817;f9db=7387;f9dc=9686;f9dd=5229;f9de=540f;f9df=5c65;f9e0" +
        "=6613;f9e1=674e;f9e2=68a8;f9e3=6ce5;f9e4=7406;f9e5=75e2;f9e6=7f79;f9e7=88cf;f9e8=88e1;f9e9=91cc;f9ea" +
        "=96e2;f9eb=533f;f9ec=6eba;f9ed=541d;f9ee=71d0;f9ef=7498;f9f0=85fa;f9f1=96a3;f9f2=9c57;f9f3=9e9f;f9f4" +
        "=6797;f9f5=6dcb;f9f6=81e8;f9f7=7acb;f9f8=7b20;f9f9=7c92;f9fa=72c0;f9fb=7099;f9fc=8b58;f9fd=4ec0;f9fe" +
        "=8336;f9ff=523a;fa00=5207;fa01=5ea6;fa02=62d3;fa03=7cd6;fa04=5b85;fa05=6d1e;fa06=66b4;fa07=8f3b;fa08" +
        "=884c;fa09=964d;fa0a=898b;fa0b=5ed3;fa0c=5140;fa0d=55c0;fa10=585a;fa12=6674;fa15=51de;fa16=732a;fa17" +
        "=76ca;fa18=793c;fa19=795e;fa1a=7965;fa1b=798f;fa1c=9756;fa1d=7cbe;fa1e=7fbd;fa20=8612;fa22=8af8;fa25" +
        "=9038;fa26=90fd;fa2a=98ef;fa2b=98fc;fa2c=9928;fa2d=9db4;fa2e=90de;fa2f=96b7;fa30=4fae;fa31=50e7;fa32" +
        "=514d;fa33=52c9;fa34=52e4;fa35=5351;fa36=559d;fa37=5606;fa38=5668;fa39=5840;fa3a=58a8;fa3b=5c64;fa3c" +
        "=5c6e;fa3d=6094;fa3e=6168;fa3f=618e;fa40=61f2;fa41=654f;fa42=65e2;fa43=6691;fa44=6885;fa45=6d77;fa46" +
        "=6e1a;fa47=6f22;fa48=716e;fa49=722b;fa4a=7422;fa4b=7891;fa4c=793e;fa4d=7949;fa4e=7948;fa4f=7950;fa50" +
        "=7956;fa51=795d;fa52=798d;fa53=798e;fa54=7a40;fa55=7a81;fa56=7bc0;fa57=7df4;fa58=7e09;fa59=7e41;fa5a" +
        "=7f72;fa5b=8005;fa5c=81ed;fa5d=8279;fa5e=8279;fa5f=8457;fa60=8910;fa61=8996;fa62=8b01;fa63=8b39;fa64" +
        "=8cd3;fa65=8d08;fa66=8fb6;fa67=9038;fa68=96e3;fa69=97ff;fa6a=983b;fa6b=6075;fa6c=242ee;fa6d=8218;fa7" +
        "0=4e26;fa71=51b5;fa72=5168;fa73=4f80;fa74=5145;fa75=5180;fa76=52c7;fa77=52fa;fa78=559d;fa79=5555;fa7" +
        "a=5599;fa7b=55e2;fa7c=585a;fa7d=58b3;fa7e=5944;fa7f=5954;fa80=5a62;fa81=5b28;fa82=5ed2;fa83=5ed9;fa8" +
        "4=5f69;fa85=5fad;fa86=60d8;fa87=614e;fa88=6108;fa89=618e;fa8a=6160;fa8b=61f2;fa8c=6234;fa8d=63c4;fa8" +
        "e=641c;fa8f=6452;fa90=6556;fa91=6674;fa92=6717;fa93=671b;fa94=6756;fa95=6b79;fa96=6bba;fa97=6d41;fa9" +
        "8=6edb;fa99=6ecb;fa9a=6f22;fa9b=701e;fa9c=716e;fa9d=77a7;fa9e=7235;fa9f=72af;faa0=732a;faa1=7471;faa" +
        "2=7506;faa3=753b;faa4=761d;faa5=761f;faa6=76ca;faa7=76db;faa8=76f4;faa9=774a;faaa=7740;faab=78cc;faa" +
        "c=7ab1;faad=7bc0;faae=7c7b;faaf=7d5b;fab0=7df4;fab1=7f3e;fab2=8005;fab3=8352;fab4=83ef;fab5=8779;fab" +
        "6=8941;fab7=8986;fab8=8996;fab9=8abf;faba=8af8;fabb=8acb;fabc=8b01;fabd=8afe;fabe=8aed;fabf=8b39;fac" +
        "0=8b8a;fac1=8d08;fac2=8f38;fac3=9072;fac4=9199;fac5=9276;fac6=967c;fac7=96e3;fac8=9756;fac9=97db;fac" +
        "a=97ff;facb=980b;facc=983b;facd=9b12;face=9f9c;facf=2284a;fad0=22844;fad1=233d5;fad2=3b9d;fad3=4018;" +
        "fad4=4039;fad5=25249;fad6=25cd0;fad7=27ed3;fad8=9f43;fad9=9f8e;fb1d=5d9;fb1f=5f2;fb2a=5e9;fb2b=5e9;f" +
        "b2c=5e9;fb2d=5e9;fb2e=5d0;fb2f=5d0;fb30=5d0;fb31=5d1;fb32=5d2;fb33=5d3;fb34=5d4;fb35=5d5;fb36=5d6;fb" +
        "38=5d8;fb39=5d9;fb3a=5da;fb3b=5db;fb3c=5dc;fb3e=5de;fb40=5e0;fb41=5e1;fb43=5e3;fb44=5e4;fb46=5e6;fb4" +
        "7=5e7;fb48=5e8;fb49=5e9;fb4a=5ea;fb4b=5d5;fb4c=5d1;fb4d=5db;fb4e=5e4;ff21=ff41;ff22=ff42;ff23=ff43;f" +
        "f24=ff44;ff25=ff45;ff26=ff46;ff27=ff47;ff28=ff48;ff29=ff49;ff2a=ff4a;ff2b=ff4b;ff2c=ff4c;ff2d=ff4d;f" +
        "f2e=ff4e;ff2f=ff4f;ff30=ff50;ff31=ff51;ff32=ff52;ff33=ff53;ff34=ff54;ff35=ff55;ff36=ff56;ff37=ff57;f" +
        "f38=ff58;ff39=ff59;ff3a=ff5a;10400=10428;10401=10429;10402=1042a;10403=1042b;10404=1042c;10405=1042d" +
        ";10406=1042e;10407=1042f;10408=10430;10409=10431;1040a=10432;1040b=10433;1040c=10434;1040d=10435;104" +
        "0e=10436;1040f=10437;10410=10438;10411=10439;10412=1043a;10413=1043b;10414=1043c;10415=1043d;10416=1" +
        "043e;10417=1043f;10418=10440;10419=10441;1041a=10442;1041b=10443;1041c=10444;1041d=10445;1041e=10446" +
        ";1041f=10447;10420=10448;10421=10449;10422=1044a;10423=1044b;10424=1044c;10425=1044d;10426=1044e;104" +
        "27=1044f;104b0=104d8;104b1=104d9;104b2=104da;104b3=104db;104b4=104dc;104b5=104dd;104b6=104de;104b7=1" +
        "04df;104b8=104e0;104b9=104e1;104ba=104e2;104bb=104e3;104bc=104e4;104bd=104e5;104be=104e6;104bf=104e7" +
        ";104c0=104e8;104c1=104e9;104c2=104ea;104c3=104eb;104c4=104ec;104c5=104ed;104c6=104ee;104c7=104ef;104" +
        "c8=104f0;104c9=104f1;104ca=104f2;104cb=104f3;104cc=104f4;104cd=104f5;104ce=104f6;104cf=104f7;104d0=1" +
        "04f8;104d1=104f9;104d2=104fa;104d3=104fb;10570=10597;10571=10598;10572=10599;10573=1059a;10574=1059b" +
        ";10575=1059c;10576=1059d;10577=1059e;10578=1059f;10579=105a0;1057a=105a1;1057c=105a3;1057d=105a4;105" +
        "7e=105a5;1057f=105a6;10580=105a7;10581=105a8;10582=105a9;10583=105aa;10584=105ab;10585=105ac;10586=1" +
        "05ad;10587=105ae;10588=105af;10589=105b0;1058a=105b1;1058c=105b3;1058d=105b4;1058e=105b5;1058f=105b6" +
        ";10590=105b7;10591=105b8;10592=105b9;10594=105bb;10595=105bc;10c80=10cc0;10c81=10cc1;10c82=10cc2;10c" +
        "83=10cc3;10c84=10cc4;10c85=10cc5;10c86=10cc6;10c87=10cc7;10c88=10cc8;10c89=10cc9;10c8a=10cca;10c8b=1" +
        "0ccb;10c8c=10ccc;10c8d=10ccd;10c8e=10cce;10c8f=10ccf;10c90=10cd0;10c91=10cd1;10c92=10cd2;10c93=10cd3" +
        ";10c94=10cd4;10c95=10cd5;10c96=10cd6;10c97=10cd7;10c98=10cd8;10c99=10cd9;10c9a=10cda;10c9b=10cdb;10c" +
        "9c=10cdc;10c9d=10cdd;10c9e=10cde;10c9f=10cdf;10ca0=10ce0;10ca1=10ce1;10ca2=10ce2;10ca3=10ce3;10ca4=1" +
        "0ce4;10ca5=10ce5;10ca6=10ce6;10ca7=10ce7;10ca8=10ce8;10ca9=10ce9;10caa=10cea;10cab=10ceb;10cac=10cec" +
        ";10cad=10ced;10cae=10cee;10caf=10cef;10cb0=10cf0;10cb1=10cf1;10cb2=10cf2;10d50=10d70;10d51=10d71;10d" +
        "52=10d72;10d53=10d73;10d54=10d74;10d55=10d75;10d56=10d76;10d57=10d77;10d58=10d78;10d59=10d79;10d5a=1" +
        "0d7a;10d5b=10d7b;10d5c=10d7c;10d5d=10d7d;10d5e=10d7e;10d5f=10d7f;10d60=10d80;10d61=10d81;10d62=10d82" +
        ";10d63=10d83;10d64=10d84;10d65=10d85;1109a=11099;1109c=1109b;110ab=110a5;1134b=11347,1133e;1134c=113" +
        "47,11357;114bb=114b9;114bc=114b9,114b0;114be=114b9,114bd;115ba=115b8,115af;115bb=115b9,115af;118a0=1" +
        "18c0;118a1=118c1;118a2=118c2;118a3=118c3;118a4=118c4;118a5=118c5;118a6=118c6;118a7=118c7;118a8=118c8" +
        ";118a9=118c9;118aa=118ca;118ab=118cb;118ac=118cc;118ad=118cd;118ae=118ce;118af=118cf;118b0=118d0;118" +
        "b1=118d1;118b2=118d2;118b3=118d3;118b4=118d4;118b5=118d5;118b6=118d6;118b7=118d7;118b8=118d8;118b9=1" +
        "18d9;118ba=118da;118bb=118db;118bc=118dc;118bd=118dd;118be=118de;118bf=118df;16e40=16e60;16e41=16e61" +
        ";16e42=16e62;16e43=16e63;16e44=16e64;16e45=16e65;16e46=16e66;16e47=16e67;16e48=16e68;16e49=16e69;16e" +
        "4a=16e6a;16e4b=16e6b;16e4c=16e6c;16e4d=16e6d;16e4e=16e6e;16e4f=16e6f;16e50=16e70;16e51=16e71;16e52=1" +
        "6e72;16e53=16e73;16e54=16e74;16e55=16e75;16e56=16e76;16e57=16e77;16e58=16e78;16e59=16e79;16e5a=16e7a" +
        ";16e5b=16e7b;16e5c=16e7c;16e5d=16e7d;16e5e=16e7e;16e5f=16e7f;16ea0=16ebb;16ea1=16ebc;16ea2=16ebd;16e" +
        "a3=16ebe;16ea4=16ebf;16ea5=16ec0;16ea6=16ec1;16ea7=16ec2;16ea8=16ec3;16ea9=16ec4;16eaa=16ec5;16eab=1" +
        "6ec6;16eac=16ec7;16ead=16ec8;16eae=16ec9;16eaf=16eca;16eb0=16ecb;16eb1=16ecc;16eb2=16ecd;16eb3=16ece" +
        ";16eb4=16ecf;16eb5=16ed0;16eb6=16ed1;16eb7=16ed2;16eb8=16ed3;1d15e=1d157,1d165;1d15f=1d158,1d165;1d1" +
        "60=1d158,1d165,1d16e;1d161=1d158,1d165,1d16f;1d162=1d158,1d165,1d170;1d163=1d158,1d165,1d171;1d164=1" +
        "d158,1d165,1d172;1d1bb=1d1b9,1d165;1d1bc=1d1ba,1d165;1d1bd=1d1b9,1d165,1d16e;1d1be=1d1ba,1d165,1d16e" +
        ";1d1bf=1d1b9,1d165,1d16f;1d1c0=1d1ba,1d165,1d16f;1e900=1e922;1e901=1e923;1e902=1e924;1e903=1e925;1e9" +
        "04=1e926;1e905=1e927;1e906=1e928;1e907=1e929;1e908=1e92a;1e909=1e92b;1e90a=1e92c;1e90b=1e92d;1e90c=1" +
        "e92e;1e90d=1e92f;1e90e=1e930;1e90f=1e931;1e910=1e932;1e911=1e933;1e912=1e934;1e913=1e935;1e914=1e936" +
        ";1e915=1e937;1e916=1e938;1e917=1e939;1e918=1e93a;1e919=1e93b;1e91a=1e93c;1e91b=1e93d;1e91c=1e93e;1e9" +
        "1d=1e93f;1e91e=1e940;1e91f=1e941;1e920=1e942;1e921=1e943;2f800=4e3d;2f801=4e38;2f802=4e41;2f803=2012" +
        "2;2f804=4f60;2f805=4fae;2f806=4fbb;2f807=5002;2f808=507a;2f809=5099;2f80a=50e7;2f80b=50cf;2f80c=349e" +
        ";2f80d=2063a;2f80e=514d;2f80f=5154;2f810=5164;2f811=5177;2f812=2051c;2f813=34b9;2f814=5167;2f815=518" +
        "d;2f816=2054b;2f817=5197;2f818=51a4;2f819=4ecc;2f81a=51ac;2f81b=51b5;2f81c=291df;2f81d=51f5;2f81e=52" +
        "03;2f81f=34df;2f820=523b;2f821=5246;2f822=5272;2f823=5277;2f824=3515;2f825=52c7;2f826=52c9;2f827=52e" +
        "4;2f828=52fa;2f829=5305;2f82a=5306;2f82b=5317;2f82c=5349;2f82d=5351;2f82e=535a;2f82f=5373;2f830=537d" +
        ";2f831=537f;2f832=537f;2f833=537f;2f834=20a2c;2f835=7070;2f836=53ca;2f837=53df;2f838=20b63;2f839=53e" +
        "b;2f83a=53f1;2f83b=5406;2f83c=549e;2f83d=5438;2f83e=5448;2f83f=5468;2f840=54a2;2f841=54f6;2f842=5510" +
        ";2f843=5553;2f844=5563;2f845=5584;2f846=5584;2f847=5599;2f848=55ab;2f849=55b3;2f84a=55c2;2f84b=5716;" +
        "2f84c=5606;2f84d=5717;2f84e=5651;2f84f=5674;2f850=5207;2f851=58ee;2f852=57ce;2f853=57f4;2f854=580d;2" +
        "f855=578b;2f856=5832;2f857=5831;2f858=58ac;2f859=214e4;2f85a=58f2;2f85b=58f7;2f85c=5906;2f85d=591a;2" +
        "f85e=5922;2f85f=5962;2f860=216a8;2f861=216ea;2f862=59ec;2f863=5a1b;2f864=5a27;2f865=59d8;2f866=5a66;" +
        "2f867=36ee;2f868=36fc;2f869=5b08;2f86a=5b3e;2f86b=5b3e;2f86c=219c8;2f86d=5bc3;2f86e=5bd8;2f86f=5be7;" +
        "2f870=5bf3;2f871=21b18;2f872=5bff;2f873=5c06;2f874=5f53;2f875=5c22;2f876=3781;2f877=5c60;2f878=5c6e;" +
        "2f879=5cc0;2f87a=5c8d;2f87b=21de4;2f87c=5d43;2f87d=21de6;2f87e=5d6e;2f87f=5d6b;2f880=5d7c;2f881=5de1" +
        ";2f882=5de2;2f883=382f;2f884=5dfd;2f885=5e28;2f886=5e3d;2f887=5e69;2f888=3862;2f889=22183;2f88a=387c" +
        ";2f88b=5eb0;2f88c=5eb3;2f88d=5eb6;2f88e=5eca;2f88f=2a392;2f890=5efe;2f891=22331;2f892=22331;2f893=82" +
        "01;2f894=5f22;2f895=5f22;2f896=38c7;2f897=232b8;2f898=261da;2f899=5f62;2f89a=5f6b;2f89b=38e3;2f89c=5" +
        "f9a;2f89d=5fcd;2f89e=5fd7;2f89f=5ff9;2f8a0=6081;2f8a1=393a;2f8a2=391c;2f8a3=6094;2f8a4=226d4;2f8a5=6" +
        "0c7;2f8a6=6148;2f8a7=614c;2f8a8=614e;2f8a9=614c;2f8aa=617a;2f8ab=618e;2f8ac=61b2;2f8ad=61a4;2f8ae=61" +
        "af;2f8af=61de;2f8b0=61f2;2f8b1=61f6;2f8b2=6210;2f8b3=621b;2f8b4=625d;2f8b5=62b1;2f8b6=62d4;2f8b7=635" +
        "0;2f8b8=22b0c;2f8b9=633d;2f8ba=62fc;2f8bb=6368;2f8bc=6383;2f8bd=63e4;2f8be=22bf1;2f8bf=6422;2f8c0=63" +
        "c5;2f8c1=63a9;2f8c2=3a2e;2f8c3=6469;2f8c4=647e;2f8c5=649d;2f8c6=6477;2f8c7=3a6c;2f8c8=654f;2f8c9=656" +
        "c;2f8ca=2300a;2f8cb=65e3;2f8cc=66f8;2f8cd=6649;2f8ce=3b19;2f8cf=6691;2f8d0=3b08;2f8d1=3ae4;2f8d2=519" +
        "2;2f8d3=5195;2f8d4=6700;2f8d5=669c;2f8d6=80ad;2f8d7=43d9;2f8d8=6717;2f8d9=671b;2f8da=6721;2f8db=675e" +
        ";2f8dc=6753;2f8dd=233c3;2f8de=3b49;2f8df=67fa;2f8e0=6785;2f8e1=6852;2f8e2=6885;2f8e3=2346d;2f8e4=688" +
        "e;2f8e5=681f;2f8e6=6914;2f8e7=3b9d;2f8e8=6942;2f8e9=69a3;2f8ea=69ea;2f8eb=6aa8;2f8ec=236a3;2f8ed=6ad" +
        "b;2f8ee=3c18;2f8ef=6b21;2f8f0=238a7;2f8f1=6b54;2f8f2=3c4e;2f8f3=6b72;2f8f4=6b9f;2f8f5=6bba;2f8f6=6bb" +
        "b;2f8f7=23a8d;2f8f8=21d0b;2f8f9=23afa;2f8fa=6c4e;2f8fb=23cbc;2f8fc=6cbf;2f8fd=6ccd;2f8fe=6c67;2f8ff=" +
        "6d16;2f900=6d3e;2f901=6d77;2f902=6d41;2f903=6d69;2f904=6d78;2f905=6d85;2f906=23d1e;2f907=6d34;2f908=" +
        "6e2f;2f909=6e6e;2f90a=3d33;2f90b=6ecb;2f90c=6ec7;2f90d=23ed1;2f90e=6df9;2f90f=6f6e;2f910=23f5e;2f911" +
        "=23f8e;2f912=6fc6;2f913=7039;2f914=701e;2f915=701b;2f916=3d96;2f917=704a;2f918=707d;2f919=7077;2f91a" +
        "=70ad;2f91b=20525;2f91c=7145;2f91d=24263;2f91e=719c;2f91f=243ab;2f920=7228;2f921=7235;2f922=7250;2f9" +
        "23=24608;2f924=7280;2f925=7295;2f926=24735;2f927=24814;2f928=737a;2f929=738b;2f92a=3eac;2f92b=73a5;2" +
        "f92c=3eb8;2f92d=3eb8;2f92e=7447;2f92f=745c;2f930=7471;2f931=7485;2f932=74ca;2f933=3f1b;2f934=7524;2f" +
        "935=24c36;2f936=753e;2f937=24c92;2f938=7570;2f939=2219f;2f93a=7610;2f93b=24fa1;2f93c=24fb8;2f93d=250" +
        "44;2f93e=3ffc;2f93f=4008;2f940=76f4;2f941=250f3;2f942=250f2;2f943=25119;2f944=25133;2f945=771e;2f946" +
        "=771f;2f947=771f;2f948=774a;2f949=4039;2f94a=778b;2f94b=4046;2f94c=4096;2f94d=2541d;2f94e=784e;2f94f" +
        "=788c;2f950=78cc;2f951=40e3;2f952=25626;2f953=7956;2f954=2569a;2f955=256c5;2f956=798f;2f957=79eb;2f9" +
        "58=412f;2f959=7a40;2f95a=7a4a;2f95b=7a4f;2f95c=2597c;2f95d=25aa7;2f95e=25aa7;2f95f=7aee;2f960=4202;2" +
        "f961=25bab;2f962=7bc6;2f963=7bc9;2f964=4227;2f965=25c80;2f966=7cd2;2f967=42a0;2f968=7ce8;2f969=7ce3;" +
        "2f96a=7d00;2f96b=25f86;2f96c=7d63;2f96d=4301;2f96e=7dc7;2f96f=7e02;2f970=7e45;2f971=4334;2f972=26228" +
        ";2f973=26247;2f974=4359;2f975=262d9;2f976=7f7a;2f977=2633e;2f978=7f95;2f979=7ffa;2f97a=8005;2f97b=26" +
        "4da;2f97c=26523;2f97d=8060;2f97e=265a8;2f97f=8070;2f980=2335f;2f981=43d5;2f982=80b2;2f983=8103;2f984" +
        "=440b;2f985=813e;2f986=5ab5;2f987=267a7;2f988=267b5;2f989=23393;2f98a=2339c;2f98b=8201;2f98c=8204;2f" +
        "98d=8f9e;2f98e=446b;2f98f=8291;2f990=828b;2f991=829d;2f992=52b3;2f993=82b1;2f994=82b3;2f995=82bd;2f9" +
        "96=82e6;2f997=26b3c;2f998=82e5;2f999=831d;2f99a=8363;2f99b=83ad;2f99c=8323;2f99d=83bd;2f99e=83e7;2f9" +
        "9f=8457;2f9a0=8353;2f9a1=83ca;2f9a2=83cc;2f9a3=83dc;2f9a4=26c36;2f9a5=26d6b;2f9a6=26cd5;2f9a7=452b;2" +
        "f9a8=84f1;2f9a9=84f3;2f9aa=8516;2f9ab=273ca;2f9ac=8564;2f9ad=26f2c;2f9ae=455d;2f9af=4561;2f9b0=26fb1" +
        ";2f9b1=270d2;2f9b2=456b;2f9b3=8650;2f9b4=865c;2f9b5=8667;2f9b6=8669;2f9b7=86a9;2f9b8=8688;2f9b9=870e" +
        ";2f9ba=86e2;2f9bb=8779;2f9bc=8728;2f9bd=876b;2f9be=8786;2f9bf=45d7;2f9c0=87e1;2f9c1=8801;2f9c2=45f9;" +
        "2f9c3=8860;2f9c4=8863;2f9c5=27667;2f9c6=88d7;2f9c7=88de;2f9c8=4635;2f9c9=88fa;2f9ca=34bb;2f9cb=278ae" +
        ";2f9cc=27966;2f9cd=46be;2f9ce=46c7;2f9cf=8aa0;2f9d0=8aed;2f9d1=8b8a;2f9d2=8c55;2f9d3=27ca8;2f9d4=8ca" +
        "b;2f9d5=8cc1;2f9d6=8d1b;2f9d7=8d77;2f9d8=27f2f;2f9d9=20804;2f9da=8dcb;2f9db=8dbc;2f9dc=8df0;2f9dd=20" +
        "8de;2f9de=8ed4;2f9df=8f38;2f9e0=285d2;2f9e1=285ed;2f9e2=9094;2f9e3=90f1;2f9e4=9111;2f9e5=2872e;2f9e6" +
        "=911b;2f9e7=9238;2f9e8=92d7;2f9e9=92d8;2f9ea=927c;2f9eb=93f9;2f9ec=9415;2f9ed=28bfa;2f9ee=958b;2f9ef" +
        "=4995;2f9f0=95b7;2f9f1=28d77;2f9f2=49e6;2f9f3=96c3;2f9f4=5db2;2f9f5=9723;2f9f6=29145;2f9f7=2921a;2f9" +
        "f8=4a6e;2f9f9=4a76;2f9fa=97e0;2f9fb=2940a;2f9fc=4ab2;2f9fd=29496;2f9fe=980b;2f9ff=980b;2fa00=9829;2f" +
        "a01=295b6;2fa02=98e2;2fa03=4b33;2fa04=9929;2fa05=99a7;2fa06=99c2;2fa07=99fe;2fa08=4bce;2fa09=29b30;2" +
        "fa0a=9b12;2fa0b=9c40;2fa0c=9cfd;2fa0d=4cce;2fa0e=4ced;2fa0f=9d67;2fa10=2a0ce;2fa11=4cf8;2fa12=2a105;" +
        "2fa13=2a20e;2fa14=2a291;2fa15=9ebb;2fa16=4d56;2fa17=9ef9;2fa18=9efe;2fa19=9f05;2fa1a=9f0f;2fa1b=9f16" +
        ";2fa1c=9f3b;2fa1d=2a600"
    const val REMOVED =
        "0,8,b,c,e,1f,7f,9f,ad,ad,300,36f,483,487,591,5bd,5bf,5bf,5c1,5c2,5c4,5c5,5c7,5c7,600,605,610,61a,61c" +
        ",61c,64b,65f,670,670,6d6,6dd,6df,6e4,6e7,6e8,6ea,6ed,70f,70f,711,711,730,74a,7a6,7b0,7eb,7f3,816,819" +
        ",81b,823,825,827,829,82d,859,85b,8e3,902,93a,93a,93c,93c,941,948,94d,94d,951,957,962,963,981,981,9bc" +
        ",9bc,9c1,9c4,9cd,9cd,9e2,9e3,a01,a02,a3c,a3c,a41,a42,a47,a48,a4b,a4d,a51,a51,a70,a71,a75,a75,a81,a82" +
        ",abc,abc,ac1,ac5,ac7,ac8,acd,acd,ae2,ae3,b01,b01,b3c,b3c,b3f,b3f,b41,b44,b4d,b4d,b56,b56,b62,b63,b82" +
        ",b82,bc0,bc0,bcd,bcd,c00,c00,c3e,c40,c46,c48,c4a,c4d,c55,c56,c62,c63,c81,c81,cbc,cbc,cbf,cbf,cc6,cc6" +
        ",ccc,ccd,ce2,ce3,d01,d01,d41,d44,d4d,d4d,d62,d63,dca,dca,dd2,dd4,dd6,dd6,e31,e31,e34,e3a,e47,e4e,eb1" +
        ",eb1,eb4,eb9,ebb,ebc,ec8,ecd,f18,f19,f35,f35,f37,f37,f39,f39,f71,f7e,f80,f84,f86,f87,f8d,f97,f99,fbc" +
        ",fc6,fc6,102d,1030,1032,1037,1039,103a,103d,103e,1058,1059,105e,1060,1071,1074,1082,1082,1085,1086,1" +
        "08d,108d,109d,109d,135d,135f,1712,1714,1732,1734,1752,1753,1772,1773,17b4,17b5,17b7,17bd,17c6,17c6,1" +
        "7c9,17d3,17dd,17dd,180b,180e,18a9,18a9,1920,1922,1927,1928,1932,1932,1939,193b,1a17,1a18,1a1b,1a1b,1" +
        "a56,1a56,1a58,1a5e,1a60,1a60,1a62,1a62,1a65,1a6c,1a73,1a7c,1a7f,1a7f,1ab0,1abd,1b00,1b03,1b34,1b34,1" +
        "b36,1b3a,1b3c,1b3c,1b42,1b42,1b6b,1b73,1b80,1b81,1ba2,1ba5,1ba8,1ba9,1bab,1bad,1be6,1be6,1be8,1be9,1" +
        "bed,1bed,1bef,1bf1,1c2c,1c33,1c36,1c37,1cd0,1cd2,1cd4,1ce0,1ce2,1ce8,1ced,1ced,1cf4,1cf4,1cf8,1cf9,1" +
        "dc0,1df5,1dfc,1dff,200b,200f,202a,202e,2060,2064,2066,206f,20d0,20dc,20e1,20e1,20e5,20f0,2cef,2cf1,2" +
        "d7f,2d7f,2de0,2dff,302a,302d,3099,309a,a66f,a66f,a674,a67d,a69e,a69f,a6f0,a6f1,a802,a802,a806,a806,a" +
        "80b,a80b,a825,a826,a8c4,a8c4,a8e0,a8f1,a926,a92d,a947,a951,a980,a982,a9b3,a9b3,a9b6,a9b9,a9bc,a9bc,a" +
        "9e5,a9e5,aa29,aa2e,aa31,aa32,aa35,aa36,aa43,aa43,aa4c,aa4c,aa7c,aa7c,aab0,aab0,aab2,aab4,aab7,aab8,a" +
        "abe,aabf,aac1,aac1,aaec,aaed,aaf6,aaf6,abe5,abe5,abe8,abe8,abed,abed,e000,f8ff,fb1e,fb1e,fe00,fe0f,f" +
        "e20,fe2f,feff,feff,fff9,fffb,fffd,fffd,101fd,101fd,102e0,102e0,10376,1037a,10a01,10a03,10a05,10a06,1" +
        "0a0c,10a0f,10a38,10a3a,10a3f,10a3f,10ae5,10ae6,11001,11001,11038,11046,1107f,11081,110b3,110b6,110b9" +
        ",110ba,110bd,110bd,11100,11102,11127,1112b,1112d,11134,11173,11173,11180,11181,111b6,111be,111ca,111" +
        "cc,1122f,11231,11234,11234,11236,11237,112df,112df,112e3,112ea,11300,11301,1133c,1133c,11340,11340,1" +
        "1366,1136c,11370,11374,114b3,114b8,114ba,114ba,114bf,114c0,114c2,114c3,115b2,115b5,115bc,115bd,115bf" +
        ",115c0,115dc,115dd,11633,1163a,1163d,1163d,1163f,11640,116ab,116ab,116ad,116ad,116b0,116b5,116b7,116" +
        "b7,1171d,1171f,11722,11725,11727,1172b,16af0,16af4,16b30,16b36,16f8f,16f92,1bc9d,1bc9e,1bca0,1bca3,1" +
        "d167,1d169,1d173,1d182,1d185,1d18b,1d1aa,1d1ad,1d242,1d244,1da00,1da36,1da3b,1da6c,1da75,1da75,1da84" +
        ",1da84,1da9b,1da9f,1daa1,1daaf,1e8d0,1e8d6,e0001,e0001,e0020,e007f,e0100,e01ef,f0000,ffffd,100000,10" +
        "fffd"
    const val PUNCTUATION =
        "21,2f,3a,40,5b,60,7b,7e,a1,a1,a7,a7,ab,ab,b6,b7,bb,bb,bf,bf,37e,37e,387,387,55a,55f,589,58a,5be,5be," +
        "5c0,5c0,5c3,5c3,5c6,5c6,5f3,5f4,609,60a,60c,60d,61b,61b,61e,61f,66a,66d,6d4,6d4,700,70d,7f7,7f9,830," +
        "83e,85e,85e,964,965,970,970,af0,af0,df4,df4,e4f,e4f,e5a,e5b,f04,f12,f14,f14,f3a,f3d,f85,f85,fd0,fd4," +
        "fd9,fda,104a,104f,10fb,10fb,1360,1368,1400,1400,166d,166e,169b,169c,16eb,16ed,1735,1736,17d4,17d6,17" +
        "d8,17da,1800,180a,1944,1945,1a1e,1a1f,1aa0,1aa6,1aa8,1aad,1b5a,1b60,1bfc,1bff,1c3b,1c3f,1c7e,1c7f,1c" +
        "c0,1cc7,1cd3,1cd3,2010,2027,2030,2043,2045,2051,2053,205e,207d,207e,208d,208e,2308,230b,2329,232a,27" +
        "68,2775,27c5,27c6,27e6,27ef,2983,2998,29d8,29db,29fc,29fd,2cf9,2cfc,2cfe,2cff,2d70,2d70,2e00,2e2e,2e" +
        "30,2e42,3001,3003,3008,3011,3014,301f,3030,3030,303d,303d,30a0,30a0,30fb,30fb,a4fe,a4ff,a60d,a60f,a6" +
        "73,a673,a67e,a67e,a6f2,a6f7,a874,a877,a8ce,a8cf,a8f8,a8fa,a8fc,a8fc,a92e,a92f,a95f,a95f,a9c1,a9cd,a9" +
        "de,a9df,aa5c,aa5f,aade,aadf,aaf0,aaf1,abeb,abeb,fd3e,fd3f,fe10,fe19,fe30,fe52,fe54,fe61,fe63,fe63,fe" +
        "68,fe68,fe6a,fe6b,ff01,ff03,ff05,ff0a,ff0c,ff0f,ff1a,ff1b,ff1f,ff20,ff3b,ff3d,ff3f,ff3f,ff5b,ff5b,ff" +
        "5d,ff5d,ff5f,ff65,10100,10102,1039f,1039f,103d0,103d0,1056f,1056f,10857,10857,1091f,1091f,1093f,1093" +
        "f,10a50,10a58,10a7f,10a7f,10af0,10af6,10b39,10b3f,10b99,10b9c,11047,1104d,110bb,110bc,110be,110c1,11" +
        "140,11143,11174,11175,111c5,111c9,111cd,111cd,111db,111db,111dd,111df,11238,1123d,112a9,112a9,114c6," +
        "114c6,115c1,115d7,11641,11643,1173c,1173e,12470,12474,16a6e,16a6f,16af5,16af5,16b37,16b3b,16b44,16b4" +
        "4,1bc9f,1bc9f,1da87,1da8b"
    const val SPACES =
        "9,d,20,20,85,85,a0,a0,1680,1680,2000,200a,2028,2029,202f,202f,205f,205f,3000,3000"
}

