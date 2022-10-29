@file:Suppress("ControlFlowWithEmptyBody", "UNCHECKED_CAST")

package borg.trikeshed.placeholder.nars


import borg.trikeshed.lib.*
import borg.trikeshed.lib.parser.simple.CharSeries
import borg.trikeshed.placeholder.nars.Rule.Companion.`^`

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

sentence ::= statement"." [tense] [truth]            (* judgement to be absorbed into beliefs *)
| statement"?" [tense]                    (* question on truth-value to be answered *)
| statement"!" [desire]                   (* goal to be realized by operations *)
| statement"@"                            (* question on desire-value to be answered *)

statement ::= <"<">term copula term<">">              (* two terms related to each other *)
| <"(">term copula term<")">              (* two terms related to each other, new notation *)
| term                                    (* a term can name a statement *)
| "(^"word {","term} ")"                  (* an operation to be executed *)
| word"("term {","term} ")"               (* an operation to be executed, new notation *)

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

compound-term ::= op-ext-set term {"," term} "}"          (* extensional set *)
| op-int-set term {"," term} "]"          (* intensional set *)
| "("op-multi"," term {"," term} ")"      (* with prefix operator *)
| "("op-single"," term "," term ")"       (* with prefix operator *)
| "(" term {op-multi term} ")"            (* with infix operator *)
| "(" term op-single term ")"             (* with infix operator *)
| "(" term {","term} ")"                  (* product, new notation *)
| "(" op-ext-image "," term {"," term} ")"(* special case, extensional image *)
| "(" op-int-image "," term {"," term} ")"(* special case, \ intensional image *)
| "(" op-negation "," term ")"            (* negation *)
| op-negation term                        (* negation, new notation *)

op-int-set::= "["                                     (* intensional set *)
op-ext-set::= "{"                                     (* extensional set *)
op-negation::= "--"                                    (* negation *)
op-int-image::= "\"                                    (* \ intensional image *)
op-ext-image::= "/"                                     (* extensional image *)
op-multi ::= "&&"                                    (* conjunction *)
| "*"                                     (* product *)
| "||"                                    (* disjunction *)
| "&|"                                    (* parallel events *)
| "&/"                                    (* sequential events *)
| "|"                                     (* intensional intersection *)
| "&"                                     (* extensional intersection *)
op-single ::= "-"                                     (* extensional difference *)
| "~"                                     (* intensional difference *)

variable ::= "$"word                                 (* independent variable *)
| "#"word                                 (* dependent variable *)
| "?"word                                 (* query variable in question *)

tense ::= ":/:"                                   (* future event *)
| ":|:"                                   (* present event *)
| ":\:"                                   (* past event *)

desire ::= truth                                   (* same format, different interpretations *)
truth ::= <"%">frequency[<";">confidence]<"%">    (* two numbers in [0,1]x(0,1) *)
budget ::= <"$">priority[<";">durability][<";">quality]<"$"> (* three numbers in [0,1]x(0,1)x[0,1] *)

word : #"[^\ ]+"                               (* unicode string *)
priority : #"([0]?\.[0-9]+|1\.[0]*|1|0)"           (* 0 <= x <= 1 *)
durability : #"[0]?\.[0]*[1-9]{1}[0-9]*"             (* 0 <  x <  1 *)
quality : #"([0]?\.[0-9]+|1\.[0]*|1|0)"           (* 0 <= x <= 1 *)
frequency : #"([0]?\.[0-9]+|1\.[0]*|1|0)"           (* 0 <= x <= 1 *)
confidence : #"[0]?\.[0]*[1-9]{1}[0-9]*"             (* 0 <  x <  1 *)
"""

//set of terminals for each nonterminal as a map:
val terminals = mapOf(
    "task" to setOf("budget", "sentence"),
    "sentence" to setOf("statement", "tense", "truth"),
    "statement" to setOf("term", "copula", "word", "variable", "compound-term", "statement"),
    "copula" to setOf("-->", "<->", "{--", "--]", "{-]", "==>", "=/>", "=|>", "=\\>", "<=>", "</>", "<|>"),
    "term" to setOf("word", "variable", "compound-term", "statement"),
    "compound-term" to setOf(
        "op-ext-set",
        "op-int-set",
        "op-negation",
        "op-int-image",
        "op-ext-image",
        "op-multi",
        "op-single"
    ),
    "op-int-set" to setOf("["),
    "op-ext-set" to setOf("{"),
    "op-negation" to setOf("--"),
    "op-int-image" to setOf("\\"),
    "op-ext-image" to setOf("/"),
    "op-multi" to setOf("&&", "*", "||", "&|", "&/", "|", "&"),
    "op-single" to setOf("-", "~"),
    "variable" to setOf("$", "#", "?"),
    "tense" to setOf(":/:", ":|:", ":\\:"),
    "desire" to setOf("truth"),
    "truth" to setOf("%"),
    "budget" to setOf("$"),
    "word" to setOf("#"),
    "priority" to setOf("#"),
    "durability" to setOf("#"),
    "quality" to setOf("#"),
    "frequency" to setOf("#"),
    "confidence" to setOf("#")
)

interface Inamed { val name: String }

typealias ParseResult = Join<
        /** the firing rule*/
        Rule,
        /**first is next, second is value/success*/
        Twin<CharSeries>>

typealias ConditionalUnaryCharOp = (CharSeries) -> CharSeries?
typealias ParseFunctor = (CharSeries) -> ParseResult?

/** the rule produces the ParseResult of the rule, or null if the rule fails */
interface Rule : ParseFunctor {
    val asm: Twin<Rule?>? get() = null
    val trim: Boolean get() = true
    val backtrack: Boolean get() = true

    override operator fun invoke(cs: CharSeries): ParseResult? {
        require(asm != null) { "default Rule implemenation with null asm" }
        //default behavior proceed for a, then b if not null and a succeeds and b succeeds
        val (a, b) = asm!!

        //when backtrack, we only execute a rule on a clone of the input
        //when trim, we skip whitespace before executing a Rule

        fun trim(cs: CharSeries) {
            while (cs.hasRemaining && cs.mk.get.isWhitespace());
            cs.res
        }

        fun doRule(r: Rule, cs: CharSeries): ParseResult? {
            if (trim) trim(cs)

            val cs0 = if (backtrack) cs.clone() else cs
            return r(cs0)
        }

        val aResult = doRule(a!!, cs)

        val let = aResult?.let { (_: Rule, i: Twin<CharSeries>): ParseResult ->
            val (next, value) = i
            when (b) {
                null -> i
                else -> doRule(b, next)?.let { (_: Rule, j: Twin<CharSeries>): ParseResult ->
                    val (next2, value2) = j
                    (next2 j (value + value2))
                }
            }
        }

        return let as ParseResult?
    }

    companion object {
        fun `^`(block: ConditionalUnaryCharOp): Rule = object : Rule {
            override fun invoke(p1: CharSeries): ParseResult? {
                val clone = p1.clone()
                val result = block(clone)
                return if (result != null) Join(this, Twin(result.slice, result.fl)) else null
            }
        }

        val nameRegistry = mutableMapOf<Rule, String>()
        fun nameOf(rule: Rule): String {
            //check  if iNamed, nameRegistry, if Enum, else use class simpleName
            return when (val r = rule) {
                is Inamed -> r.name
                is Enum<*> -> r.name
                else -> nameRegistry[rule] ?: rule::class.simpleName ?: "unknown"
            }
        }
    }
}

//upside down questionmark unicode character 0x00BF (¿)
object word : Rule by `^`({ cs: CharSeries ->
    val p = cs.pos
    while (cs.hasRemaining && !cs.mk.get.isWhitespace());
    cs.res.takeUnless { p == cs.pos }
})
