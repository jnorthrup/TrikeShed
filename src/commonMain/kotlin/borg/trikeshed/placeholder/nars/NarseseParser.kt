package borg.trikeshed.placeholder.nars

import borg.trikeshed.lib.collections._l
import borg.trikeshed.lib.parser.simple.CharSeries
import borg.trikeshed.placeholder.nars.chgroup_.Companion.digit
import borg.trikeshed.placeholder.nars.chgroup_.Companion.whitespace

typealias ConditionalUnary<T> = (T) -> T?
typealias ParseFunction = ConditionalUnary<CharSeries>
typealias `^^` = ParseFunction

// single char strings should be Char not String
// opening Char and String should use  +x operator
// a[b] is preferable to a(--b) however --b is necessary for opening parser in a parse expression
// a b is preferable to a and b , also preferable to a + b
//kotlin operator precedence is not the same as parser precedence, so we need to use parens to make it work

//narsese (oPennars) grammar
// <symbol> is a nonterminal (variable) and the __expression__ consists of one or more sequences of either terminal or nonterminal symbols;
// ::= means that the symbol on the left must be replaced with the expression on the right.
//  more sequences [of symbols] are separated by the vertical bar '|', indicating a choice, the whole being a possible substitution for the symbol on the left.
// '#' as a token prefix indicates regex pattern
// { } around a construction indicates a repeating group
//--------------------------------------------------------------------------------------------------------
//

//               task ::= [budget] sentence                       (* task to be processed *)
object task : `^^` by ( budget`¿` (digit) + sentence)

//         sentence ::= statement'.' [tense] [truth]            (* judgement to be absorbed into beliefs *)
//                    / statement'?' [tense] [truth]            (* question on truth-value to be answered *)
//                    / statement'!' [desire]                   (* goal to be realized by operations *)
//                    / statement'@' [desire]                   (* question on desire-value to be answered *)
object sentence : `^^` by (
        statement + '.' + opt_(tense) + opt_(truth) / statement + '?' + opt_(tense) + opt_(truth) / statement + '!' + opt_(
            desire
        ) / statement + '@' + opt_(desire)
        )

//           copula ::= "-->"                                   (* inheritance *)
//                    / "--]"                                   (* property *)
//                    / "<->"                                   (* similarity *)
//                    / "</>"                                   (* predictive equivalence *)
//                    / "<=>"                                   (* equivalence *)
//                    / "<|>"                                   (* concurrent equivalence *)
//                    / "=/>"                                   (* predictive implication *)
//                    / "==>"                                   (* implication *)
//                    / "=\\>"                                  (* =\> retrospective implication *)
//                    / "=|s>"                                   (* concurrent implication *)
//                    / "{--"                                   (* instance *)
//                    / "{-]"                                   (* instance-property *)
object copula :
    `^^` by ((+"-->") / "--]" / "<->" / "</>" / "<=>" / "<|>" / "=/>" / "==>" / "=\\>" / "=|>" / "{--" / "{-]")

//             term ::= word                                    (* an atomic constant term *)
//                    / variable                                (* an atomic variable term *)
//                    / compound-term                           (* a term with internal structure *)
//                    / statement                               (* a statement can serve as a term *)
object term : IOneOf, ISkipWS, `^^` {
    override val rules: Sequence<`^^`>
        get() = sequence {
            yield(word)
            yield(variable)
            yield(compound_term)
            yield(statement)
        }

    override fun invoke(p1: CharSeries): CharSeries? {
        TODO("this is intercepted in FSM")
    }
}


//             word ::= #"[^ ]+"                                (* a sequence of non-space characters *)
object word : `^^` by (!whitespace * !whitespace)


//        op-ext-set::= '{'                                     (* extensional set *)
//        op-int-set::= '['                                     (* intensional set *)
//       op-negation::= "--"                                    (* negation *)
//      op-int-image::= "\\"                                    (* \ intensional image *)
//      op-ext-image::= '/'                                     (* extensional image *)
//         op-multi ::= "&&"                                    (* conjunction *)
//                    / '*'                                     (* product *)
//                    / "||"                                    (* disjunction *)
//                    / "&|"                                    (* parallel events *)
//                    / "&/"                                    (* sequential events *)
//                    / '|'                                     (* intensional intersection *)
//                    / '&'                                     (* extensional intersection *)
//        op-single ::= '-'                                     (* extensional difference *)
//                    / '~'                                     (* intensional difference *)
object op_ext_set : `^^` by (+'{')
object op_int_set : `^^` by (+'[')
object op_negation : `^^` by (+"--")
object op_int_image : `^^` by (+"\\")
object op_ext_image : `^^` by (+'/')
object op_multi : `^^` by (+"&&" / '*' / "||" / "&|" / "&/" / '|' / '&')
object op_single : `^^` by (+'-' / '~')


//    compound-term ::= op-ext-set term {',' term} '}'          (* extensional set *)
//                    / op-int-set term {',' term} ']'          (* intensional set *)
//                    / '('op-multi',' term {',' term} ')'      (* with prefix operator *)
//                    / '('op-single',' term ',' term ')'       (* with prefix operator *)
//                    / '(' term {op-multi term} ')'            (* with infix operator *)
//                    / '(' term op-single term ')'             (* with infix operator *)
//                    / '(' term {','term} ')'                  (* product, new notation *)
//                    / '(' op-ext-image ',' term {',' term} ')'(* special case, extensional image *)
//                    / '(' op-int-image ',' term {',' term} ')'(* special case, \ intensional image *)
//                    / '(' op-negation ',' term ')'            (* negation *)
//                    / op-negation term                        (* negation, new notation *)


object compound_term : IOneOf, `^^` {
    override val rules: Sequence<`^^`> = sequence {
        yield((op_ext_set + term + ((+',') + term) * '}'))
        yield(op_int_set + term + (+',' + term) * ']')
        yield((+'(' + op_multi + ',' + term + (+',' + term) * ')'))
        yield((+'(' + op_single + ',' + term + ',' + term + ')'))
        yield((+'(' + term + (op_multi + term) * ')'))
        yield((+'(' + term + op_single + term + ')'))
        yield((+'(' + term + (+',' + term) * ')'))
        yield((+'(' + op_ext_image + ',' + term + (+',' + term) * ')'))
        yield((+'(' + op_int_image + ',' + term + (+',' + term) * ')'))
        yield((+'(' + op_negation + ',' + term + ')') / (op_negation + term))
    }


    override fun invoke(p1: CharSeries): CharSeries? {
        TODO("this is intercepted in FSM")
    }
}

//        statement ::= <'<'>term copula term<'>'>              (* two terms related to each other *)
//                    / <'('>term copula term<')'>              (* two terms related to each other, new notation *)
//                    / term                                    (* a term can name a statement *)
//                    / "(^"word {','term} ')'                  (* an operation to be executed *)
//                    / word'('term {','term} ')'               (* an operation to be executed, new notation *)
object statement :
    `^^` by (
            (+'<' + term + copula + term + '>') /
                    (+'(' + term + copula + term + ')') /
                    term /
                    (+'(' + '^' + word + (+',' + term) * ')') /
                    (word + '(' + term + (+',' + term) * ')')
            )


//         variable ::= '$'word                                 (* independent variable *)
//                    / '#'word                                 (* dependent variable *)
//                    / '?'word                                 (* query variable in question *)
object variable : `^^` by ((+'$' + word) / (+'#' + word) / (+'?' + word))

//            tense ::= ":/:"                                   (* future event *)
//                    / ":|:"                                   (* present event *)
//                    / ":\\:"                                  (* :\: past event *)
object tense : `^^` by (+":/:" / ":|:" / ":\\:")

//           desire ::= truth                                   (* same format, different interpretations *)
//            truth ::= <'%'>frequency[<';'>confidence]<'%'>    (* two numbers in [0,1]x(0,1) *)
//           budget ::= <'$'>priority[<';'>durability][<';'>quality]<'$'> (* three numbers in [0,1]x(0,1)x[0,1] *)
object desire : `^^` by (truth)
object truth : `^^` by (+'%' + frequency + (+';' + confidence) * '%')
object budget : `^^` by (+'$' + priority + (+';' + durability) * (+';' + quality) * '$')

object priority : `^^` by (float)
object durability : `^^` by (float)
object quality : `^^` by (float)
object frequency : `^^` by (float)
object confidence : `^^` by (float)
object float : `^^` by ((+_l['+', '-'] * digit * '.' * digit * (digit + +_l['E', 'e'] + +_l['+', '-'] * digit)))

