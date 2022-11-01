package borg.trikeshed.placeholder.nars

import borg.trikeshed.lib.collections._a
import borg.trikeshed.lib.parser.simple.CharSeries


/**

narsese (oPennars) grammar

Example Usage
Tim is alive.

<{Tim} --> [alive]>.

Tim is a human.

<{Tim} --> human>.

Humans are a lifeform.

<human --> lifeform>.

Lifeforms are like machines.

<lifeform <-> machine>.

Tom eats chocolate.

<(*,{Tom},chocolate) --> eat>.

<{Tom} --> (/,eat,_,chocolate)>.

<chocolate --> (/,eat,{Tom},_)>.

---- end of example usage ----

<symbol> is a nonterminal (variable) and the __expression__ consists of one or more sequences of either terminal or nonterminal symbols;
::= means that the symbol on the left must be replaced with the expression on the right.
more sequences [of symbols] are separated by the vertical bar '|', indicating a choice, the whole being a possible substitution for the symbol on the left.
'#' as a token prefix indicates regex pattern
{ } around a construction indicates a repeating group
[ ] around a construction indicates an optional group
() around a construction indicates a grouping
<> around a construction indicates a nonterminal
""
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
| "(" op-int-image "," term {"," term} ")"(* special case, intensional image *)
| "(" op-negation "," term ")"            (* negation *)
| op-negation term                        (* negation, new notation *)

op-int-set::= "["                                     (* intensional set *)
op-ext-set::= "{"                                     (* extensional set *)
op-negation::= "--"                                    (* negation *)
op-int-image::= "\"                                    (* intensional image *)
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

word : #"\S+"                               (* unicode string *)
priority : #"([0]?\.[0-9]+|1\.[0]*|1|0)"           (* 0 <= x <= 1 *)
durability : #"[0]?\.[0]*[1-9]{1}[0-9]*"             (* 0 <  x <  1 *)
quality : #"([0]?\.[0-9]+|1\.[0]*|1|0)"           (* 0 <= x <= 1 *)
frequency : #"([0]?\.[0-9]+|1\.[0]*|1|0)"           (* 0 <= x <= 1 *)
confidence : #"[0]?\.[0]*[1-9]{1}[0-9]*"             (* 0 <  x <  1 *)

----- end of narsese grammar -----

given the grammar above, Convertor will run forward pass to accumulate symbol boundary open and close candidates and
then run backward pass to build a parse AST of the task(s).

 */

/** iff an element of narsese has deterministic constants (7 or less options combined)  for begin and end we can use this to find them*/
data class symbolConfix(val begin:  Char , val end:  Char ){init{ require(' ' !in _a[ begin , end ])};companion object { fun of(begin: Char, end: Char) = symbolConfix(begin, end) } }

/** charseriescalls are:
 * - get to get the next character
 * - get(n) get at position n
 * - pos to get the current position
 * - pos(int) to set the current position
 * - lim to get the length of the input stream
 * - lim(int) to set the length of the input stream
 * - rem to get the number of remaining characters
 * - fl to flip the buffer, which sets the limit to the current position and the position to 0
 * - slice to get a new CharBuffer with the same content starting at pos until limit
 * - clone() */
typealias CharBuffer = CharSeries

