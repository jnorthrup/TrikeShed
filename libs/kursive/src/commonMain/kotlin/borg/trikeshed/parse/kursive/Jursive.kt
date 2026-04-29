package borg.trikeshed.parse.kursive

import borg.trikeshed.common.TypeEvidence
import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.MapTypeMemento
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.cursor.SeqTypeMemento
import borg.trikeshed.cursor.TypeMemento
import borg.trikeshed.cursor.joins
import borg.trikeshed.cursor.label
import borg.trikeshed.lib.CharSeries
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Twin
import borg.trikeshed.lib.asString
import borg.trikeshed.lib.α
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.plus
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.collections.s_
import borg.trikeshed.isam.meta.IOMemento

typealias NarsiveEvent = Join<Series<Char>, Twin<Int>>
typealias NarsiveTrace = Series<NarsiveEvent>

class SeriesBuffer<T>(
    capacity: Int = 8,
) : Series<T> {
    var buf: Array<Any?> = arrayOfNulls(capacity)
    var count: Int = 0

    override val a: Int get() = count
    override val b: (Int) -> T get() = { index -> buf[index] as T }

    fun add(item: T) {
        if (count == buf.size) {
            val next = arrayOfNulls<Any?>(buf.size * 2)
            buf.copyInto(next)
            buf = next
        }
        buf[count++] = item
    }

    fun removeLast(): T {
        require(count > 0) { "removeLast on empty SeriesBuffer" }
        val idx = --count
        @Suppress("UNCHECKED_CAST")
        return (buf[idx] as T).also { buf[idx] = null }
    }

    fun snapshot(): Series<T> = count j { index -> buf[index] as T }
}
//TODO DRY THESE INTO ON ABSTRACTION

class JursiveCharSeries constructor(
    val source: CharSeries,
    val sink: SeriesBuffer<NarsiveEvent>,
) : Series<Char> by source {

    constructor(source: Series<Char>) : this(CharSeries(source), SeriesBuffer())
    constructor(text: String) : this(text.toSeries())

    val pos: Int get() = source.pos
    val hasRemaining: Boolean get() = source.hasRemaining

    fun checkpoint(): Twin<Int> = source.pos j sink.size

    fun rewind(checkpoint: Twin<Int>): JursiveCharSeries = apply {
        source.pos = checkpoint.a
        while (sink.size > checkpoint.b) sink.removeLast()
    }

    fun advance(count: Int = 1): JursiveCharSeries = apply { repeat(count) { source.pos++ } }

    fun peek(): Char? = if (source.hasRemaining) source[source.pos] else null

    fun slice(start: Int, endExclusive: Int = source.pos): CharSeries = CharSeries(this[start until endExclusive])

    fun consume(expected: Char): Boolean =
        (peek() == expected).also { matched -> if (matched) source.pos++ }

    fun consume(expected: Series<Char>): Boolean {
        val checkpoint = checkpoint()
        for (index in 0 until expected.size) {
            if (!hasRemaining || this[pos] != expected[index]) {
                rewind(checkpoint)
                return false
            }
            source.pos++
        }
        return true
    }

    fun skipWhitespace(): JursiveCharSeries = apply { while (peek()?.isWhitespace() == true) source.pos++ }

    /** Try each string in order; return first successful match's CharSeries slice. Backtracks on failure. */
    fun consumeAnyOf(vararg forms: String): CharSeries? {
        for (form in forms) {
            val cp = checkpoint()
            val start = pos
            if (consume(form.toSeries())) return slice(start)
            rewind(cp)
        }
        return null
    }

    fun trace(): NarsiveTrace = sink.snapshot()

    fun emit(name: Series<Char>, start: Int, endExclusive: Int) {
        sink.add(name j (start j endExclusive))
    }
}

interface KursiveParser<T> : Join<Series<Char>, (JursiveCharSeries) -> T?> {
    val name: Series<Char> get() = a
    fun run(input: JursiveCharSeries): T? = b(input)

    operator fun invoke(input: JursiveCharSeries): T? {
        val checkpoint = input.checkpoint()
        val value = run(input)
        return if (value == null) {
            input.rewind(checkpoint)
            null
        } else {
            input.emit(name, checkpoint.a, input.pos)
            value
        }
    }
}

fun <T> parser(name: Series<Char>, op: (JursiveCharSeries) -> T?): KursiveParser<T> = object : KursiveParser<T> {
    override val a: Series<Char> = name
    override val b: (JursiveCharSeries) -> T? = op
}

fun <T> parser(name: String, op: (JursiveCharSeries) -> T?): KursiveParser<T> = object : KursiveParser<T> {
    override val a: Series<Char> = name.toSeries()
    override val b: (JursiveCharSeries) -> T? = op
}

typealias KursiveStep = (JursiveCharSeries) -> Boolean

val KursiveParser<*>.step: KursiveStep
    get() = { input -> this(input) != null }

/** shorthand: parser.s == parser.step */
val <T> KursiveParser<T>.s: KursiveStep get() = step

/** literal char step */
val Char.s: KursiveStep get() = { input -> input.consume(this) }

/** sequence two steps */
infix fun KursiveStep.then(other: KursiveStep): KursiveStep = { input -> this(input) && other(input) }

/** alternation of two steps */
infix fun KursiveStep.or(other: KursiveStep): KursiveStep = { input ->
    val cp = input.checkpoint()
    if (!this(input)) { input.rewind(cp); other(input) } else true
}

/** optional step (always succeeds) */
val KursiveStep.opt: KursiveStep get() = { input -> this(input); true }

/** repeat step at least min times */
fun KursiveStep.repeat(min: Int = 0): KursiveStep = { input ->
    var matched = 0; while (this(input)) matched++; matched >= min
}

/** step that peeks and rejects a char */
fun peekIs(predicate: (Char) -> Boolean): KursiveStep = { input -> input.peek()?.let(predicate) == true }
fun peekIsNot(c: Char): KursiveStep = { input -> input.peek() != c }

fun <T> KursiveParser<T>.named(name: Series<Char>): KursiveParser<T> =
    parser(name) { input -> this(input) }

fun <T> KursiveParser<T>.named(name: String): KursiveParser<T> =
    parser(name) { input -> this(input) }

fun bb(name: Series<Char>, vararg steps: KursiveStep): KursiveParser<CharSeries> =
    parser(name) { input ->
        val start = input.pos
        for (step in steps) if (!step(input)) return@parser null
        input.slice(start)
    }

fun bb(name: String, vararg steps: KursiveStep): KursiveParser<CharSeries> = bb(name.toSeries(), *steps)

fun maybe(step: KursiveStep): KursiveStep = { input ->
    step(input)
    true
}

fun many(step: KursiveStep, min: Int = 0): KursiveStep = { input ->
    var matched = 0
    while (step(input)) matched++
    matched >= min
}

fun <T> choice(name: Series<Char>, vararg options: KursiveParser<T>): KursiveParser<T> =
    parser(name) { input ->
        for (option in options) option(input)?.let { return@parser it }
        null
    }

fun <T> choice(name: String, vararg options: KursiveParser<T>): KursiveParser<T> = choice(name.toSeries(), *options)

fun Series<Char>.joinName(separator: Char, other: Series<Char>): Series<Char> = this + s_[separator] + other

infix fun <T> KursiveParser<T>.or(other: KursiveParser<T>): KursiveParser<T> =
    parser(name.joinName('|', other.name)) { input ->
        this(input) ?: other(input)
    }

/** cppfront-style production declaration from parser: "task" colon budgetParser */
infix fun <T> String.colon(body: KursiveParser<T>): KursiveParser<T> = body.named(this)

/** cppfront-style production declaration from composed step: "budget" colon ('$'.s then ws then num.s) */
infix fun String.colon(step: KursiveStep): KursiveParser<CharSeries> = bb(this, step)

/** Named-then composition: `(A colon B) then C` sequences B and C under A's name */
infix fun <A, B> KursiveParser<A>.then(other: KursiveParser<B>): KursiveParser<Join<A, B>> =
    parser(name.joinName('~', other.name)) { input ->
        val left = this(input) ?: return@parser null
        val right = other(input) ?: return@parser null
        left j right
    }

/** Line-chunking: split input into lines, apply parser to each, return Series of results */
fun <T> KursiveParser<T>.parseLines(text: String): Series<Join<T, NarsiveTrace>> {
    val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
    return lines.size j { index -> parse(lines[index])!! }
}

fun <T> KursiveParser<T>.parseLines(text: Series<Char>): Series<Join<T, NarsiveTrace>> =
    parseLines(text.asString())

fun <T> KursiveParser<T>.parse(input: String): Join<T, NarsiveTrace>? = parse(JursiveCharSeries(input))

fun <T> KursiveParser<T>.parse(input: Series<Char>): Join<T, NarsiveTrace>? = parse(JursiveCharSeries(input))

fun <T> KursiveParser<T>.parse(input: JursiveCharSeries): Join<T, NarsiveTrace>? =
    this(input)?.let { it j input.trace() }

fun NarsiveTrace.evidence(source: Series<Char>): Series<TypeEvidence> =
    size j { index ->
        val span = this[index].b
        source[span.a until span.b].toKursiveEvidence()
    }

fun NarsiveTrace.rowVecs(source: Series<Char>): Series<RowVec> = evidence(source) α TypeEvidence::toKursiveRowVec

fun Series<Char>.toKursiveEvidence(): TypeEvidence = TypeEvidence().apply {
    confix = detectKursiveConfix(this@toKursiveEvidence)
    structuralMemento = detectKursiveStructuralMemento(confix)
    for (index in 0 until this@toKursiveEvidence.size) this + this@toKursiveEvidence[index]
    recordColumnLength(this@toKursiveEvidence.size)
}

fun detectKursiveConfix(src: Series<Char>): String {
    if (src.size < 2) return ""
    val first = src[0]
    val last = src[src.size - 1]
    return when {
        first == '{' && last == '}' -> "{}"
        first == '[' && last == ']' -> "[]"
        first == '"' && last == '"' -> "\"\""
        first == '\'' && last == '\'' -> "''"
        first == '<' && last == '>' -> "<>"
        first == '(' && last == ')' -> "()"
        else -> ""
    }
}

fun detectKursiveStructuralMemento(confix: String): TypeMemento? =
    when (confix) {
        "{}" -> MapTypeMemento
        "[]" -> SeqTypeMemento
        else -> null
    }

fun TypeEvidence.toKursiveRowVec(): RowVec {
    val values = arrayOf<Any?>(
        confix,
        digits.toInt(),
        periods.toInt(),
        exponent.toInt(),
        signs.toInt(),
        special.toInt(),
        alpha.toInt(),
        truefalse.toInt(),
        empty.toInt(),
        quotes.toInt(),
        dquotes.toInt(),
        whitespaces.toInt(),
        backslashes.toInt(),
        linefeed.toInt(),
        maxColumnLength.toInt(),
        if (minColumnLength == UShort.MAX_VALUE) 0 else minColumnLength.toInt(),
        TypeEvidence.deduceMemento(this).label,
    )
    val meta = KURSIVE_EVIDENCE_COLUMNS.size j { index: Int -> { KURSIVE_EVIDENCE_COLUMNS[index] } }
    return values.size j { index: Int -> values[index] } joins meta
}

val KURSIVE_EVIDENCE_COLUMNS = arrayOf(
    ColumnMeta("confix", IOMemento.IoString),
    ColumnMeta("digits", IOMemento.IoInt),
    ColumnMeta("periods", IOMemento.IoInt),
    ColumnMeta("exponent", IOMemento.IoInt),
    ColumnMeta("signs", IOMemento.IoInt),
    ColumnMeta("special", IOMemento.IoInt),
    ColumnMeta("alpha", IOMemento.IoInt),
    ColumnMeta("truefalse", IOMemento.IoInt),
    ColumnMeta("empty", IOMemento.IoInt),
    ColumnMeta("quotes", IOMemento.IoInt),
    ColumnMeta("dquotes", IOMemento.IoInt),
    ColumnMeta("whitespaces", IOMemento.IoInt),
    ColumnMeta("backslashes", IOMemento.IoInt),
    ColumnMeta("linefeed", IOMemento.IoInt),
    ColumnMeta("maxColumnLength", IOMemento.IoInt),
    ColumnMeta("minColumnLength", IOMemento.IoInt),
    ColumnMeta("deducedType", IOMemento.IoString),
)

/** First character as a single-element Series, or null if empty */
fun Series<Char>.firstGlyphOrNull(): Series<Char>? =
    if (size == 0) null else 1 j { this[0] }
