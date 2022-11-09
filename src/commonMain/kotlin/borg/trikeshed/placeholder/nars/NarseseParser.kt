@file:Suppress("ControlFlowWithEmptyBody", "UNCHECKED_CAST")

package borg.trikeshed.placeholder.nars

import borg.trikeshed.common.collections.Stack
import borg.trikeshed.common.parser.simple.CharSeries
import borg.trikeshed.lib.*


import borg.trikeshed.placeholder.nars.ParseContext.Key
import borg.trikeshed.placeholder.nars.Rule.Companion.`^`
import borg.trikeshed.placeholder.nars.chgroup_.Companion.digit
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.CoroutineContext

val selfDoc = """ 
    
 single char strings should be Char not String
 opening Char and String should use  +x operator
 a[b] is preferable to a(--b) however --b is necessary for opening parser in a parse expression
 a b is preferable to a and b , also preferable to a + b
kotlin operator precedence is not the same as parser precedence, so we need to use parens to make it work

narsese (oPennars) grammar
 <symbol> is a nonterminal (variable) and the __expression__ consists of one or more sequences of either terminal or nonterminal symbols;
 ::= means that the symbol on the left must be replaced with the expression on the right.
  more sequences [of symbols] are separated by the vertical bar '|', indicating a choice, the whole being a possible substitution for the symbol on the left.
 '#' as a token prefix indicates regex pattern
 { } around a construction indicates a repeating group
--------------------------------------------------------------------------------------------------------
task ::= [budget] sentence                       (* task to be processed *)

sentence ::= statement'.' [tense] [truth]            (* judgement to be absorbed into beliefs *)
| statement'?' [tense]                    (* question on truth-value to be answered *)
| statement'!' [desire]                   (* goal to be realized by operations *)
| statement'@'                            (* question on desire-value to be answered *)

statement ::= <'<'>term copula term<'>'>              (* two terms related to each other *)
| <'('>term copula term<')'>              (* two terms related to each other, new notation *)
| term                                    (* a term can name a statement *)
| "(^"word {','term} ')'                  (* an operation to be executed *)
| word'('term {','term} ')'               (* an operation to be executed, new notation *)

copula ::= "-->"                                   (* inheritance *)
| "<->"                                   (* similarity *)
| "{--"                                   (* instance *)
| "--]"                                   (* property *)
| "{-]"                                   (* instance-property *)
| "==>"                                   (* implication *)
| "=/>"                                   (* predictive implication *)
| "=|>"                                   (* concurrent implication *)
| "=\>"                                  (* =\> retrospective implication *)
| "<=>"                                   (* equivalence *)
| "</>"                                   (* predictive equivalence *)
| "<|>"                                   (* concurrent equivalence *)

term ::= word                                    (* an atomic constant term *)
| variable                                (* an atomic variable term *)
| compound-term                           (* a term with internal structure *)
| statement                               (* a statement can serve as a term *)

compound-term ::= op-ext-set term {',' term} '}'          (* extensional set *)
| op-int-set term {',' term} ']'          (* intensional set *)
| '('op-multi',' term {',' term} ')'      (* with prefix operator *)
| '('op-single',' term ',' term ')'       (* with prefix operator *)
| '(' term {op-multi term} ')'            (* with infix operator *)
| '(' term op-single term ')'             (* with infix operator *)
| '(' term {','term} ')'                  (* product, new notation *)
| '(' op-ext-image ',' term {',' term} ')'(* special case, extensional image *)
| '(' op-int-image ',' term {',' term} ')'(* special case, \ intensional image *)
| '(' op-negation ',' term ')'            (* negation *)
| op-negation term                        (* negation, new notation *)

op-int-set::= '['                                     (* intensional set *)
op-ext-set::= '{'                                     (* extensional set *)
op-negation::= "--"                                    (* negation *)
op-int-image::= '\'                                    (* \ intensional image *)
op-ext-image::= '/'                                     (* extensional image *)
op-multi ::= "&&"                                    (* conjunction *)
| '*'                                     (* product *)
| "||"                                    (* disjunction *)
| "&|"                                    (* parallel events *)
| "&/"                                    (* sequential events *)
| '|'                                     (* intensional intersection *)
| '&'                                     (* extensional intersection *)
op-single ::= '-'                                     (* extensional difference *)
| '~'                                     (* intensional difference *)

variable ::= '$'word                                 (* independent variable *)
| '#'word                                 (* dependent variable *)
| '?'word                                 (* query variable in question *)

tense ::= ":/:"                                   (* future event *)
| ":|:"                                   (* present event *)
| ":\:"                                   (* past event *)

desire ::= truth                                   (* same format, different interpretations *)
truth ::= <'%'>frequency[<';'>confidence]<'%'>    (* two numbers in [0,1]x(0,1) *)
budget ::= <'$'>priority[<';'>durability][<';'>quality]<'$'> (* three numbers in [0,1]x(0,1)x[0,1] *)

word : #"[^\ ]+"                               (* unicode string *)
priority : #"([0]?\.[0-9]+|1\.[0]*|1|0)"           (* 0 <= x <= 1 *)
durability : #"[0]?\.[0]*[1-9]{1}[0-9]*"             (* 0 <  x <  1 *)
quality : #"([0]?\.[0-9]+|1\.[0]*|1|0)"           (* 0 <= x <= 1 *)
frequency : #"([0]?\.[0-9]+|1\.[0]*|1|0)"           (* 0 <= x <= 1 *)
confidence : #"[0]?\.[0]*[1-9]{1}[0-9]*"             (* 0 <  x <  1 *)
"""


interface INamed {
    val name: String
}

typealias ConditionalUnaryCharOp = (CharSeries) -> CharSeries?


typealias ParseResult = Join<
        /** the firing rule*/
        Rule,
        /** first is next as a slice, second is flipped buffer value/success*/
        Twin<CharSeries>>

val ParseResult.next get() = b.a
val ParseResult.rule get() = a
val ParseResult.value get() = b.b

typealias ParseFunctor = (suspend (CharSeries) -> ParseResult?)


/** the rule produces the ParseResult of the rule, or null if the rule fails */
interface Rule : ParseFunctor {
    val asm: Twin<Rule?>? get() = null
    val trim: Boolean get() = true
    val backtrack: Boolean get() = true


    val Rule.name: String
        get() {
            return nameOf(this)
        }

    override suspend operator fun invoke(cs: CharSeries): ParseResult? {
        require(asm != null) { "default Rule implemenation with null asm" }
        //default behavior proceed for a, then b if not null and a succeeds and b succeeds
        val (a, b) = asm!!

        //when backtrack, we only execute a rule on a clone of the input
        //when trim, we skip whitespace before executing a Rule

        fun trim(cs: CharSeries) {
            while (cs.hasRemaining && cs.mk.get.isWhitespace());
            cs.res
        }

        suspend fun doRule(r: Rule, cs: CharSeries): ParseResult? {
            if (trim) trim(cs)

            val cs0 = if (backtrack) cs.clone() else cs
            return r(cs0)
        }

        val aResult = doRule(a!!, cs)

        val res = aResult?.let { (_: Rule, i: Twin<CharSeries>): ParseResult ->
            val (next, value) = i
            when (b) {
                null -> i
                else -> doRule(b, next)?.let { (_: Rule, j: Twin<CharSeries>): ParseResult ->
                    val (next2, value2) = j
                    (next2 j (value + value2))
                }
            }
        }
        return (res as? ParseResult)?.snitch()
    }

    operator fun dec(): Rule = this * noop


    companion object {
        fun `^`(block: ConditionalUnaryCharOp): Rule = object : Rule {
            override suspend fun invoke(p1: CharSeries): ParseResult? {
                val clone = p1.clone()
                val result = block(clone)

                return result?.let { result ->
                    val a1 = result.slice
                    val b1 = result.fl
                    (ParseResult(
                        this,
                        Twin(a1, b1)
                    ) as? ParseResult)?.snitch()
                }
            }

            override fun toString() = "Rule: " + name
        }

        val nameRegistry = mutableMapOf<Rule, String>()
        fun nameOf(rule: Rule): String {
            //check  if iNamed, nameRegistry, if Enum, else use class simpleName
            return when (val r = rule) {
                is INamed -> r.name
                is Enum<*> -> r.name
                is CoroutineContext.Element -> "${r.key}"
                else -> nameRegistry[rule] ?: rule::class.simpleName ?: "unknown"
            }
        }
    }
}

suspend fun ParseResult.snitch() = apply {
    lateinit var parseContext: ParseContext
    lateinit var currentCoroutineContext: CoroutineContext

    currentCoroutineContext = currentCoroutineContext()
    parseContext = currentCoroutineContext[Key] ?: ParseContext().apply {
        currentCoroutineContext + this
    }
    parseContext.stack.push(this@apply)
}

fun skipWs(r: Rule): Rule = object : Rule {
    override suspend fun invoke(p1: CharSeries): ParseResult? {
        while (p1.hasRemaining && p1.mk.get.isWhitespace());
        return if (p1.hasRemaining) r(p1.res) else r(p1)
    }
}

//perform a OR b on the input CharSeries and return the result of the first rule that succeeds
operator fun Rule.div(other: Rule): Rule {
    return object : Rule {
        override val asm: Twin<Rule?> = Twin(this@div, other)
        override val trim: Boolean = true
        override val backtrack: Boolean = true

        override fun toString(): String {
            return "OR: ${asm.pair.first} / ${asm.pair.second}"

        }

        override suspend operator fun invoke(cs: CharSeries): ParseResult? {

            require(asm != null) { "A OR B Rule implemenation with null asm" }
            //default behavior proceed for a, then b if not null and a succeeds and b succeeds
            val (a, b) = asm

            //when backtrack, we only execute a rule on a clone of the input
            //when trim, we skip whitespace before executing a Rule

            fun trim(cs: CharSeries) {
                while (cs.hasRemaining && cs.mk.get.isWhitespace());
                cs.res
            }

            suspend fun doRule(r: Rule, cs: CharSeries): ParseResult? {
                if (trim) trim(cs)

                val cs0 = if (backtrack) cs.clone() else cs
                return r(cs0)
            }
            //clone cs, then perform a, and if not a perform b on new clone
            return (doRule(a!!, cs.clone()) ?: doRule(b!!, cs.clone()))?.snitch()
        }
    }
}

//there is a Stack in the coroutine context that can be used to store state between calls to the parse coroutine
class ParseContext : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<ParseContext>

    override val key: CoroutineContext.Key<*> get() = Key
    val stack = Stack<ParseResult>()
}


object word : Rule by `^`({ cs: CharSeries ->
    val p = cs.pos
    while (cs.hasRemaining && !cs.mk.get.isWhitespace());
    cs.res.takeUnless { p == cs.pos }
})

object noop : Rule by `^`({ cs -> cs })

operator fun Rule.plus(b: ParseFunctor): Rule = this + b
operator fun ConditionalUnaryCharOp.unaryPlus(): Rule = `^`(this)

operator fun Char.unaryPlus(): ConditionalUnaryCharOp =
    { cs: CharSeries -> this.let { char -> cs.takeIf { it.get == char } } }


//kotlin/java unquoted legal currency symbols are: $¢£¤¥₠₡₢₣₤₥₦₧₨₩₪₫€₭₮₯₰₱₲₳₴₵₶₷₸₹₺₻₼₽₾₿
//legal unquoted greek symbols in kotlin are: αβγδεζηθικλμνξοπρστυφχψω

val String.λ: Rule get() = (+(+this))["lit $this"]
val Char.λ: Rule get() = (+(+this))["lit '$this'"]

operator fun String.unaryPlus(): ConditionalUnaryCharOp = { cs ->
    var c = 0
    while (c < this.length && cs.hasRemaining && cs.mk.get == this[c]) c++
    cs.res.takeIf { c == this.length }
}

//combine two rules into a single rule with a + b  or to continue to replace b with a new rule a,b, recurse
operator fun Rule.plus(b: Rule): Rule = asm?.let { twin ->
    val (a, c) = twin
    c?.let { c1 -> a!! + (c1 + b) } ?: (a!! + b)
} ?: object : Rule {
    override val asm: Twin<Rule?> = Twin(this@plus, b)
}

infix fun Rule.`¿`(r: Rule): Rule = opt_(this) + r

fun opt_(r: Rule): Rule = `^` { cs ->
    runBlocking {
        val clone = cs.clone()
        val result = r(clone)
        if (result != null) cs.res
        else noop(cs) as CharSeries
    }
}

infix fun ConditionalUnaryCharOp.`¿`(r: ConditionalUnaryCharOp): Rule = `^` { cs ->
    val clone = cs.clone()
    val result = this(clone)
    result?.let { r(it) }
}


object float_ :
    Rule by (chgroup_("+-") `¿` digit) * ('.'.λ + (+digit)[1..9]) `¿` +chgroup_("eE") `¿` +chgroup_("+-") `¿` (+digit)[1..4]

//0 or more Rule*Rule  operator = opt_(this) +other
operator fun Rule.times(always: Rule): Rule = opt_(this)[-1] + always

//operator get = repeat_ n  times, -1 = infinite until fail then backtrack
operator fun Rule.get(n: Int): Rule = object : Rule {
    override suspend fun invoke(cs: CharSeries): ParseResult? {
        var i = 0
        var result: ParseResult? = null
        var clone = cs.clone()
        while (i != n) {
            val rule = this@get
            result = rule.invoke(clone)
            if (result == null) break
            clone = result.next

            i++
        }
        return result?.takeIf { i == n || n == -1 }?.snitch()
    }
}


operator fun Rule.get(rge: IntRange): Rule = this[rge.first] + (opt_(this))[rge.last - rge.first]


//priority : #"([0]?\.[0-9]+|1\.[0]*|1|0)"           (* 0 <= x <= 1 *)

object priority : Rule by ((+chgroup_("10")))[1..1] + (+digit)[1..9] `¿` +(+('.')) `¿` ((+digit))[1..9]

//durability : #"[0]?\.[0]*[1-9]{1}[0-9]*"             (* 0 <  x <  1 *)

object durability : Rule by ((+chgroup_("10")))[1..1] + (+digit)[1..9] `¿` +(+('.')) `¿` ((+digit))[1..9]


//quality : #"([0]?\.[0-9]+|1\.[0]*|1|0)"           (* 0 <= x <= 1 *)

object quality : Rule by ((+chgroup_("10")))[1..1] + (+digit)[1..9] `¿` +(+('.')) `¿` ((+digit))[1..9]


//frequency : #"([0]?\.[0-9]+|1\.[0]*|1|0)"           (* 0 <= x <= 1 *)

object frequency : Rule by ((+chgroup_("10")))[1..1] + (+digit)[1..9] `¿` +(+('.')) `¿` ((+digit))[1..9]

//confidence : #"[0]?\.[0]*[1-9]{1}[0-9]*"             (* 0 <  x <  1 *)

object confidence : Rule by ((+chgroup_("10")))[1..1] + (+digit)[1..9] `¿` +(+('.')) `¿` ((+digit))[1..9]

//copula ::= "-->"                                   (* inheritance *)
//| "<->"                                   (* similarity *)
//| "{--"                                   (* instance *)
//| "--]"                                   (* property *)
//| "{-]"                                   (* instance-property *)
//| "==>"                                   (* implication *)
//| "=/>"                                   (* predictive implication *)
//| "=|>"                                   (* concurrent implication *)
//| "=\>"                                  (* =\> retrospective implication *)
//| "<=>"                                   (* equivalence *)
//| "</>"                                   (* predictive equivalence *)
//| "<|>"                                   (* concurrent equivalence *)

object copula : Rule by "-->".λ["inheritance"] /
        "<->".λ["similarity"] /
        "{--".λ["instance"] /
        "--]".λ["property"] /
        "{-]".λ["instance-property"] /
        "==>".λ["implication"] /
        "=/>".λ["predictive implication"] /
        "=|>".λ["concurrent implication"] /
        "=\\>".λ["retrospective implication"] /
        "<=>".λ["equivalence"] /
        "</>".λ["predictive equivalence"] /
        "<|>".λ["concurrent equivalence"]


operator fun Rule.get(name: String): Rule = object : INamed, Rule {
    override val name: String = name
    override suspend fun invoke(cs: CharSeries): ParseResult? = this@get(cs)

}

//term ::= word                                    (* an atomic constant term *)
//| variable                                (* an atomic variable term *)
//| compound-term                           (* a term with internal structure *)
//| statement                               (* a statement can serve as a term *)
object term : Rule by word["atomicconstant"] /
        variable["atomicvariable)"] /
        compound_term["internal structure"] /
        statement["statement"]

//compound-term ::= op-ext-set term {',' term} '}'          (* extensional set *)
//| op-int-set term {',' term} ']'          (* intensional set *)
//| '('op-multi',' term {',' term} ')'      (* with prefix operator *)
//| '('op-single',' term ',' term ')'       (* with prefix operator *)
//| '(' term {op-multi term} ')'            (* with infix operator *)
//| '(' term op-single term ')'             (* with infix operator *)
//| '(' term {','term} ')'                  (* product, new notation *)
//| '(' op-ext-image ',' term {',' term} ')'(* special case, extensional image *)
//| '(' op-int-image ',' term {',' term} ')'(* special case, \ intensional image *)
//| '(' op-negation ',' term ')'            (* negation *)
//| op-negation term                        (* negation, new notation *)
//
//op-int-set::= '['                                     (* intensional set *)
//op-ext-set::= '{'                                     (* extensional set *)
//op-negation::= "--"                                    (* negation *)
//op-int-image::= '\'                                    (* \ intensional image *)
//op-ext-image::= '/'                                     (* extensional image *)
//op-multi ::= "&&"                                    (* conjunction *)
//| '*'                                     (* product *)
//| "||"                                    (* disjunction *)
//| "&|"                                    (* parallel events *)
//| "&/"                                    (* sequential events *)
//| '|'                                     (* intensional intersection *)
//| '&'                                     (* extensional intersection *)
//op-single ::= '-'                                     (* extensional difference *)
//| '~'                                     (* intensional difference *)

object termSet : Rule by (','.λ + term)[-1]


object compound_term : Rule by '['.λ["intensional set"] + term + termSet * ']'.λ /
        '{'.λ["extensional set"] + term + termSet + '}'.λ /
        ('(' λ "&&".λ["conjunction"] /
                '*'.λ["product"] /
                "||".λ["disjunction"] /
                "&|".λ["parallel events"] /
                "&/".λ["sequential events"] /
                '|'.λ["intensional intersection"] /
                '&'.λ["extensional intersection"] /
                '-'.λ["extensional difference"] /
                '~'.λ["intensional difference"] /
                '/'.λ["special case, extensional image"] /
                "\\".λ["special case, intensional image"] /
                "--".λ["negation"] /
                (noop["product alt"] + term + termSet))(term + termSet + ')'.λ) /
        "--".λ["negation"] + term


infix operator fun Rule.times(noop: noop) = opt_(this)[-1]

infix operator fun Rule.invoke(rule: Rule): InfixRule = InfixRule(this, rule)

infix operator fun InfixRule.invoke(rule: Rule): ConfixRule = ConfixRule(this, rule)

/**
 * a Rule override which uses a j b to override asm
 */
class InfixRule(a: Rule, b: Rule, override val asm: Twin<Rule?>? = Twin(a, b)) : Rule

/**
 * a Rule override which uses a j b to override asm
 */
class ConfixRule(a: Rule, b: Rule, override val asm: Twin<Rule?>? = Twin(a, b)) : Rule

//sentence ::= statement'.' [tense] [truth]            (* judgement to be absorbed into beliefs *)
//| statement'?' [tense]                    (* question on truth-value to be answered *)
//| statement'!' [desire]                   (* goal to be realized by operations *)
//| statement'@'                            (* question on desire-value to be answered *)

object sentence : Rule by statement + '.'.λ["judgement to be absorbed into beliefs"] +
        (tense `¿` truth `¿` noop) /
        statement + '?'.λ["question on truth-value to be answered"] + (tense `¿` noop) /
        statement + '!'.λ["goal to be realized by operations"] + (desire `¿` noop) /
        statement + '@'.λ["question on desire-value to be answered"]


//statement ::= <'<'>term copula term<'>'>              (* two terms related to each other *)
//| <'('>term copula term<')'>              (* two terms related to each other, new notation *)
//| term                                    (* a term can name a statement *)
//| "(^"word {','term} ')'                  (* an operation to be executed *)
//| word'('term {','term} ')'               (* an operation to be executed, new notation *)

object statement : Rule by '<'.λ + term + copula + term + '>'.λ /
        '('.λ + term + copula + term + ')'.λ /
        term /
        ('('.λ) + ('^'.λ) + word + (','.λ + term) * ')'.λ /
        word + '('.λ + term + (','.λ + term) * ')'.λ


//variable ::= '$'word                      (* independent variable *)
//| '#'word                                 (* dependent variable *)
//| '?'word                                 (* query variable in question *)

object variable : Rule by '$'.λ + word["independent variable"] /
        '#'.λ + word["dependent variable"] /
        '?'.λ + word["query variable in question"]

//tense ::= ":/:"                                   (* future event *)
//| ":|:"                                   (* present event *)
//| ":\:"                                   (* past event *)
object tense : Rule by ":/:".λ["future event"] /
        ":|:".λ["present event"] /
        ":\\:".λ["past event"]

//desire ::= truth                                   (* same format, different interpretations *)
object desire : Rule by truth

//truth ::= <'%'>frequency[<';'>confidence]<'%'>    (* two numbers in [0,1]x(0,1) *)
object truth : Rule by ('%'.λ) + frequency + (+(+(';')) + confidence) * '%'.λ

//budget ::= <'$'>priority[<';'>durability][<';'>quality]<'$'> (* three numbers in [0,1]x(0,1)x[0,1] *)
object budget : Rule by '$'.λ + priority + (';'.λ + durability) * ((';'.λ + quality) * '$'.λ)

// task ::= [budget] sentence                       (* task to be processed *)
object task : Rule by (budget `¿` sentence)




