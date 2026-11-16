package borg.trikeshed.mlir

import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.channels.consume
import kotlin.jvm.JvmInline

/**
 * `kif-to-nix` ahead of the Graal/LLVM fork: the kif dialect (mlir-nix `include/NixDialect/Kif.td`) lowered to a pure
 * Nix expression, from `kif.theory` text, without the native mlnix library.
 *
 * The IR is PRELOAD-DIALECTS step 6: a `kif.list` is a [Series] of terms and a theory is its facts and its rules.
 * Mirrors `lib/Dialect/KifDialect.cpp` (`KifToNixPass`): an atom and a variable become a string, a list a list, a rule
 * `[ "=>" antecedent consequent ]`, and the theory `{ facts = [ ... ]; rules = [ ... ]; }`, the input sumo-nix's
 * `datalog.nix` reads. Terms are nodes of a graph region, so a term may use one defined after it; equal terms read
 * from one node are one [KifNode] here too.
 *
 *     module     = [ 'module' '{' ] theory { theory } [ '}' ]
 *     theory     = 'kif.theory' '@' name [ 'attributes' attrs ] '{' { statement } '}'
 *     statement  = value '=' definition [ attrs ] | 'kif.fact' value [ attrs ] | 'kif.rule' value 'implies' value [ attrs ]
 *     definition = 'kif.atom' string | 'kif.var' string | 'kif.list' '(' [ value { ',' value } ] ')'
 *     value      = '%' name
 *
 * Any other operation is refused as upstream refuses it: `sumo.*` facts need `sumo-to-kif` first.
 */
sealed interface KifNode
@JvmInline value class KifAtom(val name: String) : KifNode
@JvmInline value class KifVar(val name: String) : KifNode
@JvmInline value class KifList(val elements: Series<KifNode>) : KifNode

/** `(=> antecedent consequent)` as antecedent j consequent. */
typealias KifRule = Twin<KifNode>

/** A `kif.theory`: its symbol, then its facts and its rules in the order the theory holds them. */
typealias KifTheory = Join<String, Join<Series<KifNode>, Series<KifRule>>>

val KifTheory.name: String get() = a
val KifTheory.facts: Series<KifNode> get() = b.a
val KifTheory.rules: Series<KifRule> get() = b.b

/** Nix source text. */
typealias NixText = String

/** A theory's symbol j the Nix expression it lowers to. */
typealias NixExpr = Join<String, NixText>

/** The Nix expression of each theory in [mlir]. */
fun kifToNix(mlir: CharSequence): Series<NixExpr> =
    CharSeries(mlir).kifTheories().view.map { it.name j it.nix() }.toSeries()

fun KifTheory.nix(): NixText =
    "{ facts = ${nixList(facts α { it.nix() })}; rules = ${nixList(rules α { (ante, cons) -> nixList(s_["\"=>\"", ante.nix(), cons.nix()]) })}; }"

fun KifNode.nix(): NixText = when (this) {
    is KifAtom -> nixString(name)
    is KifVar -> nixString(name)
    is KifList -> nixList(elements α { it.nix() })
}

fun nixList(items: Series<NixText>): NixText =
    if (items.size == 0) "[ ]" else items.view.joinToString(" ", "[ ", " ]")

/** A Nix string literal: `"`, `\`, control characters and `${` escaped, everything else as it is. */
fun nixString(text: String): NixText = buildString {
    append('"')
    for (i in text.indices) when (val c = text[i]) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        '$' -> append(if (i + 1 < text.length && text[i + 1] == '{') "\\$" else "$")
        else -> append(c)
    }
    append('"')
}

/** Whitespace and `//` comments. */
fun CharSeries.blank() {
    while (hasRemaining) {
        if (this[pos].isWhitespace()) pos++
        else if (consume("//")) while (hasRemaining && this[pos] != '\n') pos++
        else return
    }
}

fun CharSeries.expect(c: Char) {
    blank()
    require(consume(c)) { "kif: expected '$c' at $pos" }
}

fun Char.isWordChar(): Boolean = isLetterOrDigit() || this == '_' || this == '.' || this == '$' || this == '-'

/** `[A-Za-z0-9_.$-]+`: an operation, a symbol or a value name. */
fun CharSeries.word(): String {
    val start = pos
    while (hasRemaining && this[pos].isWordChar()) pos++
    require(pos > start) { "kif: expected a name at $start" }
    return CharSeries(this[start j pos]).asString()
}

/** [text] as a whole word, consumed; otherwise nothing is. */
fun CharSeries.keyword(text: String): Boolean {
    blank()
    val start = pos
    if (!consume(text) || (hasRemaining && this[pos].isWordChar())) { pos = start; return false }
    return true
}

/** The operation name at the next statement. */
fun CharSeries.operation(): String {
    blank()
    return word()
}

/** A MLIR string literal as text: `\n`, `\t`, `\\`, `\"` and `\hh` bytes, which must decode as UTF-8 together. */
fun CharSeries.mlirString(): String {
    expect('"')
    val text = StringBuilder()
    val bytes = mutableListOf<Byte>()
    fun flush() {
        if (bytes.isNotEmpty()) text.append(bytes.toByteArray().decodeToString(throwOnInvalidSequence = true))
        bytes.clear()
    }
    while (true) {
        require(hasRemaining) { "kif: unterminated string" }
        val c = get
        if (c == '"') { flush(); return text.toString() }
        if (c != '\\') { flush(); text.append(c); continue }
        require(hasRemaining) { "kif: unterminated string" }
        when (val e = get) {
            'n' -> bytes.add(10)
            't' -> bytes.add(9)
            '\\' -> bytes.add(92)
            '"' -> bytes.add(34)
            else -> {
                require(hasRemaining) { "kif: unterminated string" }
                val hi = e.digitToIntOrNull(16)
                val lo = get.digitToIntOrNull(16)
                require(hi != null && lo != null) { "kif: bad escape at $pos" }
                bytes.add((hi * 16 + lo).toByte())
            }
        }
    }
}

/** `{ ... }` kept balanced and unread: an `attributes` dictionary or an operation's attr-dict. */
fun CharSeries.skipBraces() {
    expect('{')
    var depth = 1
    while (depth > 0) {
        require(hasRemaining) { "kif: unbalanced '{'" }
        when (this[pos]) {
            '{' -> { pos++; depth++ }
            '}' -> { pos++; depth-- }
            '"' -> mlirString()
            else -> pos++
        }
    }
}

fun CharSeries.attrDict() {
    blank()
    if (hasRemaining && this[pos] == '{') skipBraces()
}

fun CharSeries.value(): String {
    expect('%')
    return "%" + word()
}

fun notKif(op: String): Nothing = throw IllegalArgumentException("$op is not KIF: lower it with sumo-to-kif first")

fun CharSeries.kifTheories(): Series<KifTheory> {
    val theories = mutableListOf<KifTheory>()
    val wrapped = keyword("module")
    if (wrapped) expect('{')
    while (true) {
        blank()
        if (!hasRemaining || this[pos] == '}') break
        theories.add(kifTheory())
    }
    if (wrapped) expect('}')
    blank()
    require(!hasRemaining) { "kif: unexpected text at $pos" }
    return theories.toSeries()
}

fun CharSeries.kifTheory(): KifTheory {
    val op = operation()
    if (op != KifOps.theory.qualifiedName) notKif(op)
    expect('@')
    val symbol = word()
    if (keyword("attributes")) skipBraces()
    expect('{')
    // Operands may name a value defined later (a graph region), so terms resolve once the body is read.
    val leaves = HashMap<String, KifNode>()
    val lists = HashMap<String, Series<String>>()
    val facts = mutableListOf<String>()
    val rules = mutableListOf<Twin<String>>()
    while (true) {
        blank()
        require(hasRemaining) { "kif: unclosed theory @$symbol" }
        if (this[pos] == '}') { pos++; break }
        if (this[pos] == '%') {
            val id = value()
            require(id !in leaves && id !in lists) { "kif: $id is defined twice" }
            expect('=')
            when (val def = operation()) {
                KifOps.atom.qualifiedName -> leaves[id] = KifAtom(mlirString())
                KifOps.variable.qualifiedName -> {
                    val name = mlirString()
                    require(name.startsWith("?") && name.length >= 2) { "kif.var: a variable's name starts with ? and has a name after it" }
                    leaves[id] = KifVar(name)
                }
                KifOps.list.qualifiedName -> {
                    expect('(')
                    val operands = mutableListOf<String>()
                    blank()
                    require(hasRemaining) { "kif: unterminated kif.list" }
                    if (this[pos] != ')') do operands.add(value()) while (consumeComma())
                    expect(')')
                    lists[id] = operands.toSeries()
                }
                else -> notKif(def)
            }
        } else when (val statement = operation()) {
            KifOps.fact.qualifiedName -> facts.add(value())
            KifOps.rule.qualifiedName -> {
                val antecedent = value()
                require(keyword("implies")) { "kif.rule: expected 'implies' at $pos" }
                rules.add(antecedent j value())
            }
            else -> notKif(statement)
        }
        attrDict()
    }
    val done = HashMap<String, KifNode>()
    val resolving = HashSet<String>()
    fun term(id: String): KifNode {
        done[id]?.let { return it }
        leaves[id]?.let { return it }
        val operands = lists[id] ?: error("kif: $id is not defined in @$symbol")
        require(resolving.add(id)) { "kif: $id contains itself" }
        // Eager: a lazy projection would defer the cycle check past this call.
        val node = KifList(operands.view.map { term(it) }.toSeries())
        resolving.remove(id)
        done[id] = node
        return node
    }
    return symbol j (facts.map { term(it) }.toSeries() j rules.map { (ante, cons) -> term(ante) j term(cons) }.toSeries())
}

/** A `,` between operands, or false. */
fun CharSeries.consumeComma(): Boolean {
    blank()
    return consume(',')
}
