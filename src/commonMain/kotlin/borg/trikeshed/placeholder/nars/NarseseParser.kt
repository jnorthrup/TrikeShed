package borg.trikeshed.placeholder.nars

import borg.trikeshed.lib.parser.simple.* // ktlint-disable no-wildcard-imports


/** the partial lift alias*/
typealias `-^` = NarseseParser

//when the parser precedence exceeds the kotlin operator precedence we need to insert parens to make it work
sealed interface NarseseParser : CharParser {

    /** the "lift" operator. this ctor does work as a conversion but operator++ was my first choice and requires an
     * immediate parent class as receiver.  no uncles or cousins. */

    class `^^`(var op: CharParser) : NarseseParser {
        override fun invoke(p1: CharSeries): CharSeries? = op(p1)
        override fun toString(): String = "^^($op)"

        companion object {
            infix operator fun invoke(op: CharParser): `-^` = `^^`(op)
        }
    }


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
    object task : `-^` by `^^`(opt_(budget) + sentence)

    //         sentence ::= statement'.' [tense] [truth]            (* judgement to be absorbed into beliefs *)
    //                    / statement'?' [tense] [truth]            (* question on truth-value to be answered *)
    //                    / statement'!' [desire]                   (* goal to be realized by operations *)
    //                    / statement'@' [desire]                   (* question on desire-value to be answered *)
    object sentence : `-^` by `^^`(
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
    //                    / "=|>"                                   (* concurrent implication *)
    //                    / "{--"                                   (* instance *)
    //                    / "{-]"                                   (* instance-property *)
    object copula :`-^` by `^^`((+"-->") / "--]" / "<->" / "</>" / "<=>" / "<|>" / "=/>" / "==>" / "=\\>" / "=|>" / "{--" / "{-]"  )

    //             term ::= word                                    (* an atomic constant term *)
    //                    / variable                                (* an atomic variable term *)
    //                    / compound-term                           (* a term with internal structure *)
    //                    / statement                               (* a statement can serve as a term *)
    object term : `-^` by `^^`(word / variable / compound_term / statement)

    //             word ::= #"[^ ]+"                                (* a sequence of non-space characters *)
    object word : `-^` by `^^`(!chgroup_.whitespace*!chgroup_.whitespace)


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
    object op_ext_set : `-^` by `^^`(+'{')
    object op_int_set : `-^` by `^^`(+'[')
    object op_negation : `-^` by `^^`(+"--")
    object op_int_image : `-^` by `^^`(+"\\")
    object op_ext_image : `-^` by `^^`(+'/')
    object op_multi : `-^` by `^^`(+"&&" / '*' / "||" / "&|" / "&/" / '|' / '&')
    object op_single : `-^` by `^^`(+'-' / '~')


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

    object compound_term :
        `-^` by `^^`(
            op_ext_set + term + ((+',' )+ term)* '}' /
                    op_int_set + term + (+',' + term) * ']' /
                    (+'(' + op_multi + ',' + term + (+',' + term) * ')' )/
                    (+'(' + op_single + ',' + term + ',' + term + ')' )/
                    (+'(' + term + (op_multi + term) * ')' )/
                    (+'(' + term + op_single + term + ')' )/
                    (+'(' + term + (+',' + term) * ')' )/
                    (+'(' + op_ext_image + ',' + term + (+',' + term) * ')' )/
                    (+'(' + op_int_image + ',' + term + (+',' + term) * ')' )/
                    (+'(' + op_negation + ',' + term + ')' )/
                    op_negation + term
        )


    //        statement ::= <'<'>term copula term<'>'>              (* two terms related to each other *)
//                    / <'('>term copula term<')'>              (* two terms related to each other, new notation *)
//                    / term                                    (* a term can name a statement *)
//                    / "(^"word {','term} ')'                  (* an operation to be executed *)
//                    / word'('term {','term} ')'               (* an operation to be executed, new notation *)
    object statement :
        `-^` by `^^`(
            (+'<' + term + copula + term + '>') /
                    (+'(' + term + copula + term + ')') /
                    term /
                    (+'(' + '^' + word + (+',' + term) * ')') /
                    (word + '(' + term + (+',' + term) * ')')
        )


//         variable ::= '$'word                                 (* independent variable *)
//                    / '#'word                                 (* dependent variable *)
//                    / '?'word                                 (* query variable in question *)
    object variable :
        `-^` by `^^`((+'$' + word) / (+'#' + word) / (+'?' + word))

//            tense ::= ":/:"                                   (* future event *)
//                    / ":|:"                                   (* present event *)
//                    / ":\\:"                                  (* :\: past event *)
    object tense : `-^` by `^^`(+":/:" / ":|:" / ":\\:")

//           desire ::= truth                                   (* same format, different interpretations *)
//            truth ::= <'%'>frequency[<';'>confidence]<'%'>    (* two numbers in [0,1]x(0,1) *)
//           budget ::= <'$'>priority[<';'>durability][<';'>quality]<'$'> (* three numbers in [0,1]x(0,1)x[0,1] *)
    object desire : `-^` by `^^`(truth)
    object truth : `-^` by `^^`(+'%' + frequency + (+';' + confidence) * '%')
    object budget : `-^` by `^^`(+'$' + priority + (+';' + durability) * (+';' + quality) * '$')

    object priority : `-^` by `^^`(float)
    object durability : `-^` by `^^`(float)
    object quality : `-^` by `^^`(float)
    object frequency : `-^` by `^^`(float)
    object confidence : `-^` by `^^`(float)

    object float : `-^` by `^^`(+'0' * '.' + '0' * chgroup_.digit * chgroup_.digit / +'0' * chgroup_.digit * chgroup_.digit + '.' * (+'0') * chgroup_.digit * chgroup_.digit)

}
