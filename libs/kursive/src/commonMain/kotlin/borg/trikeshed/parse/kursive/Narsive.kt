package borg.trikeshed.parse.kursive

import borg.trikeshed.context.BitMaskedLong
import borg.trikeshed.lib.CharSeries
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Twin
import borg.trikeshed.lib.asString
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.userspace.concurrency.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

// ── element kinds ──────────────────────────────────────────────

enum class NarsiveElementKind(
    val parserName: String,
    val isRoot: Boolean = false,
    private val operatorLexeme: (Series<Char>) -> Series<Char>? = { null },
    private val operatorAccept: (NarsiveOperator) -> Boolean = { false },
) : CoroutineContext.Key<NarsiveElement> {
    TASK("narsiveTask", true),
    SENTENCE("narsiveSentence", true),
    JUDGEMENT("narsiveJudgement"),
    GOAL("narsiveGoal"),
    QUESTION("narsiveQuestion"),
    BUDGET("narsiveBudget"),
    TRUTH("narsiveTruth"),
    TENSE("narsiveTense", operatorLexeme = { it }, operatorAccept = { it.isTense }),
    STATEMENT("narsiveStatement"),
    RELATIONSHIP("narsiveRelationship"),
    COPULA("narsiveCopula", operatorLexeme = { it }, operatorAccept = { it.isCopula }),
    TERM("narsiveTerm"),
    WORD("narsiveWord"),
    VARIABLE("narsiveVariable", operatorLexeme = { it.firstGlyphOrNull() }, operatorAccept = { it.isVariable }),
    OPERATION("narsiveOperation"),
    COMPOUND_TERM("narsiveCompoundTerm"),
    CONJUNCTION("narsiveConjunction", operatorLexeme = { it }, operatorAccept = { it.isConjunction }),
    NUMERIC("narsiveNumeric"),
    QUOTED("narsiveQuoted"),
    UNKNOWN("narsiveUnknown"),
    ;

    val parserSeries: Series<Char> = parserName.toSeries()

    fun matchesParser(label: Series<Char>): Boolean = parserName == label.asString()

    fun operatorOrNull(lexeme: Series<Char>): NarsiveOperator? =
        operatorLexeme(lexeme)?.narsiveOperatorOrNull(operatorAccept)

    fun render(
        lexeme: Series<Char>,
        mode: NarsiveRenderMode = NarsiveRenderMode.MATCHING_UNICODE,
    ): Series<Char> = operatorLexeme(lexeme)?.let { projected ->
        projected.narsiveOperatorOrNull(operatorAccept)?.render(mode, projected)
    } ?: lexeme

    companion object {
        fun fromParser(label: Series<Char>): NarsiveElementKind =
            entries.firstOrNull { it.matchesParser(label) } ?: UNKNOWN
    }
}

// ── element carrier ────────────────────────────────────────────

class NarsiveElement(
    val kind: NarsiveElementKind,
    val span: Twin<Int>,
    val lexeme: Series<Char>,
) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = kind

    fun operatorOrNull(): NarsiveOperator? = kind.operatorOrNull(lexeme)

    fun render(mode: NarsiveRenderMode = NarsiveRenderMode.MATCHING_UNICODE): Series<Char> =
        kind.render(lexeme, mode)
}

// ── supervisor job ─────────────────────────────────────────────

class NarsiveSupervisorJob(
    val root: NarsiveElement,
    val elements: Series<NarsiveElement>,
) : AbstractCoroutineContextElement(Job), Job {
    private val supervisor = SupervisorJob()

    override fun isActive(): Boolean = supervisor.isActive

    override fun cancel(cause: CancellationException?) {
        supervisor.cancel(cause)
    }

    fun fanout(key: CoroutineContext.Key<*>): Series<NarsiveElement> {
        val matches = SeriesBuffer<NarsiveElement>()
        for (index in 0 until elements.size) if (elements[index].key === key) matches.add(elements[index])
        return matches.snapshot()
    }

    fun fanout(kind: NarsiveElementKind): Series<NarsiveElement> = fanout(kind as CoroutineContext.Key<*>)

    fun concurrentResolutions(): Series<Series<NarsiveElement>> =
        (NarsiveElementKind.entries.size - 1) j { index -> fanout(NarsiveElementKind.entries[index]) }
}

// ── render mode ─────────────────────────────────────────────────

enum class NarsiveRenderMode {
    ASCII,
    CO_DEFINED,
    MATCHING_UNICODE,
}

// ── operator enum ───────────────────────────────────────────────

enum class NarsiveOperator(
    val asciiForm: String,
    val unicodeForm: String? = null,
    vararg val aliases: String,
) : BitMaskedLong {
    INHERITANCE("-->", "→", "⟶"),
    SIMILARITY("<->", "↔", "⟷"),
    EQUIVALENCE("<=>", "⇔", "⟺"),
    IMPLICATION("==>", "⇒", "⟹"),
    PREDICTIVE_IMPLICATION("/>", "⇝", "⤳"),
    CONCURRENT_IMPLICATION("=|>", "⇢", "⤇"),
    INTERSECTION("&&", "∧", "⋀"),
    UNION("||", "∨", "⋁"),
    SEQUENTIAL("&/", "⩘"),
    PARALLEL("&|", "⩚"),
    PRODUCT("*", "×", "⋅"),
    FUTURE(":/:", "◷"),
    PAST(":\\:", "◶"),
    PRESENT(":|:", "◴"),
    QUERY_VARIABLE("?"),
    DEPENDENT_VARIABLE("#"),
    INDEPENDENT_VARIABLE("$"),
    ;

    private val forms: Array<String> = (listOfNotNull(asciiForm, unicodeForm) + aliases.asList()).toTypedArray()
    private val orderedForms: Array<String> = forms.sortedByDescending(String::length).toTypedArray()

    val isCopula: Boolean get() = ordinal <= CONCURRENT_IMPLICATION.ordinal
    val isConjunction: Boolean get() = ordinal in INTERSECTION.ordinal..PRODUCT.ordinal
    val isTense: Boolean get() = ordinal in FUTURE.ordinal..PRESENT.ordinal
    val isVariable: Boolean get() = ordinal >= QUERY_VARIABLE.ordinal

    fun matches(lexeme: Series<Char>): Boolean = forms.any { it == lexeme.asString() }

    fun tryConsume(input: KursiveCharSeries): CharSeries? = input.consumeAnyOf(*orderedForms)

    fun render(mode: NarsiveRenderMode = NarsiveRenderMode.MATCHING_UNICODE, matched: Series<Char>? = null): Series<Char> = when (mode) {
        NarsiveRenderMode.ASCII -> asciiForm.toSeries()
        NarsiveRenderMode.CO_DEFINED -> (unicodeForm ?: asciiForm).toSeries()
        NarsiveRenderMode.MATCHING_UNICODE -> {
            val matchedText = matched?.asString()
            when {
                matchedText != null && matchedText != asciiForm && forms.any { it == matchedText } -> matchedText.toSeries()
                unicodeForm != null -> unicodeForm.toSeries()
                else -> asciiForm.toSeries()
            }
        }
    }
}

// ── operator lexing helpers ─────────────────────────────────────────────

private fun KursiveCharSeries.consumeAnyOf(vararg forms: String): CharSeries? {
    for (form in forms) {
        val checkpoint = checkpoint()
        val start = pos
        if (consume(form.toSeries())) return slice(start)
        rewind(checkpoint)
    }
    return null
}

internal fun Series<Char>.narsiveOperatorOrNull(
    accept: (NarsiveOperator) -> Boolean = { true },
): NarsiveOperator? = NarsiveOperator.entries.firstOrNull { accept(it) && it.matches(this) }

internal fun KursiveCharSeries.consumeNarsiveOperatorOrNull(
    accept: (NarsiveOperator) -> Boolean = { true },
): CharSeries? = NarsiveOperator.entries.firstNotNullOfOrNull { operator ->
    if (accept(operator)) operator.tryConsume(this) else null
}

private fun Series<Char>.firstGlyphOrNull(): Series<Char>? = if (size == 0) null else 1 j { this[0] }

// ── narsive grammar ─────────────────────────────────────────────

object Narsive {

    // ── leaf productions ──

    val word: KursiveParser<CharSeries> =
        "narsiveWord" colon std.takeWhile("wordAtom", min = 1) { it.isLetterOrDigit() || it == '_' || it == '-' || it == '\'' || it == '/' }

    val numeric: KursiveParser<CharSeries> =
        "narsiveNumeric" colon parser("num") { std.number(it) }

    val quoted: KursiveParser<CharSeries> =
        "narsiveQuoted" colon parser("qot") { std.quoted(it) }

    // ── operator productions ──

    /** copula : any isCopula operator */
    val copula: KursiveParser<CharSeries> =
        "narsiveCopula" colon parser("copula") { input -> input.consumeNarsiveOperatorOrNull { it.isCopula } }

    /** conjunction : any isConjunction operator */
    val conjunction: KursiveParser<CharSeries> =
        "narsiveConjunction" colon parser("conjunction") { input -> input.consumeNarsiveOperatorOrNull { it.isConjunction } }

    /** tense : any isTense operator */
    val tense: KursiveParser<CharSeries> =
        "narsiveTense" colon parser("tense") { input -> input.consumeNarsiveOperatorOrNull { it.isTense } }

    /** variable : any isVariable operator followed by a word atom */
    val variable: KursiveParser<CharSeries> =
        "narsiveVariable" colon parser("variable") { input ->
            val prefix = input.consumeNarsiveOperatorOrNull { it.isVariable }
            prefix?.let { std.takeWhile("varName", min = 0) { c -> c.isLetterOrDigit() || c == '_' }(input); it }
        }

    // ── term (lazy for mutual recursion with relationship/operation/compoundTerm) ──

    /** term : relationship | operation | compoundTerm | variable | quoted | word
     *  Uses indirect step to break the mutual-recursion init cycle.
     *  [termStep] reads [_term] at parse time, not at lazy-init time.
     */
    private var _term: KursiveParser<CharSeries>? = null
    private val termStep: KursiveStep get() = { input -> _term!!(input) != null }
    private val termParser: KursiveParser<CharSeries> get() = _term!!

    val term: KursiveParser<CharSeries> get() = _term!!

    // ── structural productions ──

    /** budget : '$' num (';' num)? '$' */
    val budget: KursiveParser<CharSeries> =
        "narsiveBudget" colon ('$'.s then std.wss then std.nums then (std.wss then ';'.s then std.wss then std.nums).opt then std.wss then '$'.s)

    /** truth : '%' num (';' num)? '%' */
    val truth: KursiveParser<CharSeries> =
        "narsiveTruth" colon ('%'.s then std.wss then std.nums then (std.wss then ';'.s then std.wss then std.nums).opt then std.wss then '%'.s)

    /** relationship : '<' term copula term '>' */
    val relationship: KursiveParser<CharSeries> by lazy(LazyThreadSafetyMode.NONE) {
        "narsiveRelationship" colon ('<'.s then std.wss then termStep then std.wss then copula.s then std.wss then termStep then std.wss then '>'.s)
    }

    /** operation : '(' '^' word (',' term)+ ')' */
    val operation: KursiveParser<CharSeries> by lazy(LazyThreadSafetyMode.NONE) {
        "narsiveOperation" colon ('('.s then std.wss then '^'.s then word.s then (std.wss then ','.s then std.wss then termStep).repeat(1) then std.wss then ')'.s)
    }

    /** compound : '(' conjunction (',' term){2,} ')' */
    val compoundTerm: KursiveParser<CharSeries> by lazy(LazyThreadSafetyMode.NONE) {
        "narsiveCompoundTerm" colon ('('.s then std.wss then peekIsNot('^') then conjunction.s then (std.wss then ','.s then std.wss then termStep).repeat(2) then std.wss then ')'.s)
    }

    /** statement : relationship | operation | term */
    val statement: KursiveParser<CharSeries> by lazy(LazyThreadSafetyMode.NONE) {
        "narsiveStatement" colon choice("stmtAlt", relationship, operation, termParser)
    }

    /** judgement : statement '.' tense? truth? */
    val judgement: KursiveParser<CharSeries> by lazy(LazyThreadSafetyMode.NONE) {
        "narsiveJudgement" colon (statement.s then std.wss then '.'.s then std.wss then tense.s.opt then std.wss then truth.s.opt)
    }

    /** goal : statement '!' truth? */
    val goal: KursiveParser<CharSeries> by lazy(LazyThreadSafetyMode.NONE) {
        "narsiveGoal" colon (statement.s then std.wss then '!'.s then std.wss then truth.s.opt)
    }

    /** question : statement '?' tense? */
    val questionSentence: KursiveParser<CharSeries> by lazy(LazyThreadSafetyMode.NONE) {
        "narsiveQuestion" colon (statement.s then std.wss then '?'.s then std.wss then tense.s.opt)
    }

    /** sentence : judgement | goal | question */
    val sentence: KursiveParser<CharSeries> by lazy(LazyThreadSafetyMode.NONE) {
        "narsiveSentence" colon choice("sentAlt", judgement, goal, questionSentence)
    }

    /** task : budget? sentence */
    val task: KursiveParser<CharSeries> by lazy(LazyThreadSafetyMode.NONE) {
        "narsiveTask" colon (std.wss then budget.s.opt then std.wss then sentence.s then std.wss)
    }

    // ── resolve lateinit ──

    init {
        _term = choice("narsiveTerm", relationship, operation, compoundTerm, variable, quoted, word)
    }

    // ── entry points ──

    fun parseTask(text: String): Join<CharSeries, NarsiveTrace>? = task.parse(text)

    fun parseTask(text: Series<Char>): Join<CharSeries, NarsiveTrace>? = task.parse(text)

    fun parseSentence(text: String): Join<CharSeries, NarsiveTrace>? = sentence.parse(text)

    fun parseSentence(text: Series<Char>): Join<CharSeries, NarsiveTrace>? = sentence.parse(text)

    fun parseTasks(text: String): Series<Join<CharSeries, NarsiveTrace>> = task.parseLines(text)

    fun parseTasks(text: Series<Char>): Series<Join<CharSeries, NarsiveTrace>> = task.parseLines(text)
}

// ── trace → element/supervisor adapters ──────────────────────────────────

fun NarsiveTrace.elements(source: Series<Char>): Series<NarsiveElement> =
    size j { index ->
        val event = this[index]
        val span = event.b
        val lexeme = source[span.a until span.b]
        NarsiveElement(NarsiveElementKind.fromParser(event.a), span, lexeme)
    }

fun Join<CharSeries, NarsiveTrace>.supervisorJob(source: Series<Char>): NarsiveSupervisorJob {
    val elements = b.elements(source)
    var first: NarsiveElement? = null
    var root: NarsiveElement? = null
    for (index in 0 until elements.size) {
        val element = elements[index]
        if (first == null) first = element
        if (element.kind.isRoot) root = element
    }
    return NarsiveSupervisorJob(root ?: first ?: NarsiveElement(NarsiveElementKind.UNKNOWN, 0 j 0, "".toSeries()), elements)
}

fun Series<NarsiveElement>.operatorMask(): Long =
    (0 until size).mapNotNull { index -> this[index].operatorOrNull() }.narsiveMask()

fun Long.narsiveOperators(): Set<NarsiveOperator> =
    NarsiveOperator.entries.filter { (this and it.mask) != 0L }.toSet()

fun Iterable<NarsiveOperator>.narsiveMask(): Long =
    fold(0L) { acc, operator -> acc or operator.mask }
