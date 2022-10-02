package borg.trikeshed.placeholder.parser

/**
 # the NARS grammar (NARSese)

 The following intro defines the NARS grammar in EBNF

## I/O Format

Each line in the input and output of NARS is either a task (as defined in the following grammar, in BNF notation), or an integer, indicating the number of inference steps between tasks.

In a task, all the space characters are optional and will be ignored by the system in processing.

### Narsese EBNF Grammar
Narsese is the formal language used by NARS for internal representation and external communication. The language is defined by the following context-free grammar.

 *  description comments are the confix between `(*` , `*)`
 *  literal symbols are quoted

```ebnf
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
```


 ### Example Usage
Tim is alive.

`<{Tim} --> [alive]>.`

Tim is a human.

`<{Tim} --> human>.`

Humans are a lifeform.

`<human --> lifeform>.`

Lifeforms are like machines.

`<lifeform <-> machine>.`

Tom eats chocolate.

```
<(*,{Tom},chocolate) --> eat>.

<{Tom} --> (/,eat,_,chocolate)>.

<chocolate --> (/,eat,{Tom},_)>.
 ```
*/

