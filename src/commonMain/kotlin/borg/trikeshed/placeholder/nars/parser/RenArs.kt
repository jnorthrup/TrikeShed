package borg.trikeshed.placeholder.nars.parser

// the bnf tokens as regex:
enum class RenArs(val re: Regex) {
    //  task ::= [budget] sentence                       (* task to be processed *)
    Task(Regex("""(?<budget>\$[0-9]+(\.[0-9]+)?;[0-9]+(\.[0-9]+)?;[0-9]+(\.[0-9]+)?\$)?(?<sentence>.*)""")),

    //         sentence ::= statement"." [tense] [truth]            (* judgement to be absorbed into beliefs *)
//                    | statement"?" [tense]                    (* question on truth-value to be answered *)
//                    | statement"!" [desire]                   (* goal to be realized by operations *)
//                    | statement"@"                            (* question on desire-value to be answered *)
    Sentence(Regex("""(?<statement>.*)[\.?!@](?<tense>:[/|\\]:)?(?<truth>%[0-9]+(\.[0-9]+)?;[0-9]+(\.[0-9]+)?%)?""")),

    //        statement ::= <"<">term copula term<">">              (* two terms related to each other *)
//                    | <"(">term copula term<")">              (* two terms related to each other, new notation *)
//                    | term                                    (* a term can name a statement *)
//                    | "(^"word {","term} ")"                  (* an operation to be executed *)
//                    | word"("term {","term} ")"               (* an operation to be executed, new notation *)
    Statement(Regex("""(?<statement><.*>|\(.*\)|[^\.?!@]+)(?<operation>\(\^(?<word>[^\ ,]+)(,(?<term>[^,]+))*\))?(?<operation2>[^\(]*\((?<term2>[^,]+)(,(?<term3>[^,]+))*\))?""")),

    //           copula ::= "-->"                                   (* inheritance *)
//                    | "<->"                                   (* similarity *)
//                    | "{--"                                   (* instance *)
//                    | "--]"                                   (* property *)
//                    | "{-]"                                   (* instance-property *)
//                    | "==>"                                   (* implication *)
//                    | "=/>"                                   (* predictive implication *)
//                    | "=|>"                                   (* concurrent implication *)
//                    | "=\>"                                  (* =\> retrospective implication *)
//                    | "<=>"                                   (* equivalence *)
//                    | "</>"                                   (* predictive equivalence *)
//                    | "<|>"                                   (* concurrent equivalence *)
    Copula(Regex("""(?<copula>--->|<->|\{--|--]|\{-]|==>|=/>|=|>|=\\>|<=>|</>|<|>)""")),

    //             term ::= word                                    (* an atomic constant term *)
//                    | variable                                (* an atomic variable term *)
//                    | compound-term                           (* a term with internal structure *)
//                    | statement                               (* a statement can serve as a term *)
    Term(Regex("""(?<term>[^,\{\}\[\]\(\) ]+)(?<statement>\(.*\)|[^\.?!@]+)?""")),

    //    compound-term ::= op-ext-set term {"," term} "}"          (* extensional set *)
//                    | op-int-set term {"," term} "]"          (* intensional set *)
//                    | "("op-multi"," term {"," term} ")"      (* with prefix operator *)
//                    | "("op-single"," term
//                           {"," term} ")"                    (* with prefix operator *)
//                    | "(" term {op-multi term} ")"            (* with infix operator *)
//                    | "(" term {op-single term} ")"           (* with infix operator *)
//                    | "("op-ext-image"," term "," term ")"    (* extensional image *)
//                    | "("op-int-image"," term "," term ")"    (* intensional image *)
//                    | "("op-negation"," term ")"              (* negation *)
//                    | "(" term ")"                            (* parentheses *)
    CompoundTerm(Regex("""(?<compound>\{.*\}|\[.*\]|\(.*\)|[^,\{\}\[\]\(\) ]+)(?<statement>\(.*\)|[^\.?!@]+)?""")),

    //        op-int-set::= "["                                     (* intensional set *)
//        op-ext-set::= "{"                                     (* extensional set *)
//       op-negation::= "--"                                    (* negation *)
//      op-int-image::= "\"                                    (* \ intensional image *)
//      op-ext-image::= "/"                                     (* extensional image *)
//         op-multi ::= "&&"                                    (* conjunction *)
//                    | "*"                                     (* product *)
//                    | "||"                                    (* disjunction *)
//                    | "&|"                                    (* parallel events *)
//                    | "&/"                                    (* sequential events *)
//                    | "|"                                     (* intensional intersection *)
//                    | "&"                                     (* extensional intersection *)
//        op-single ::= "-"                                     (* extensional difference *)
//                    | "~"                                     (* intensional difference *)
    OpIntSet(Regex("""(?<op>\[)""")),
    OpExtSet(Regex("""(?<op>\{)""")),
    OpNegation(Regex("""(?<op>--)""")),
    OpIntImage(Regex("""(?<op>\\)""")),
    OpExtImage(Regex("""(?<op>/)""")),
    OpMulti(Regex("""(?<op>&&|\*|\|\||&\||&/|\||&)""")),
    OpSingle(Regex("""(?<op>-|~)""")),

    //         variable ::= "$"word                                 (* independent variable *)
//                    | "#"word                                 (* dependent variable *)
//                    | "?"word                                 (* query variable
    Variable(Regex("""(?<variable>\$[^\ ,]+|#\w+\??|\?\w+)""")),

    //           word ::= letter {letter}                           (* a word is a sequence of letters *)
    Word(Regex("""(?<word>\w+)""")),

    //          letter ::= "A".."Z" | "a".."z" | "0".."9" | "_"     (* a letter is a character *)
    Letter(Regex("""(?<letter>\w)""")),

    //           tense ::= ":" ["/" | "\" | "|"] ":"                (* tense of the sentence *)
    Tense(Regex("""(?<tense>:[/|\\]:)""")),

    //           truth ::= "%"float";"float"%"                      (* truth-value of the sentence *)
    Truth(Regex("""(?<truth>%[0-9]+(\.[0-9]+)?;[0-9]+(\.[0-9]+)?%)""")),

    //          desire ::= "&"float";"float"&"                      (* desire-value of the sentence *)
    Desire(Regex("""(?<desire>&[0-9]+(\.[0-9]+)?;[0-9]+(\.[0-9]+)?&)""")),

    //        float ::= digit {digit} ["." digit {digit}]           (* a floating point number *)
    Float(Regex("""(?<float>[0-9]+(\.[0-9]+)?)""")),

    //          digit ::= "0".."9"                                  (* a digit is a character *)
    Digit(Regex("""(?<digit>\d)""")),

    //        comment ::= "//" {any}                                (* comment *)
    Comment(Regex("""(?<comment>//.*)""")),
}