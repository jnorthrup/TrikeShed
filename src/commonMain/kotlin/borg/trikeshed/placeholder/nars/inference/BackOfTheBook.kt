package borg.trikeshed.placeholder.nars.inference

import kotlinx.collections.immutable.*

import borg.trikeshed.placeholder.nars.*
/*
    APPENDIX A
    NARSESE GRAMMAR
    The complete list of Narsese grammar rules are in Table A.1.
    The grammar rules in this book are written in a variant of the
    Backus-Naur Form (BNF), specified as the following:
    • Each rule has the format “symbol ::= expression”, where the
    symbol is a nonterminal, and the expression consists of a sequence
    of symbols, as substitution for the symbol.
    • Symbols that never appear on a left side are terminals that are
    specified by definitions in the book.
    • Symbols without the “” are used literally. Quotation makers are
    used to avoid confusion if a symbol is also used for other purpose,
    as in the following.
    • Expression “exp1|exp2” indicates alternative substitutions.
    • Expression “[symbol]” indicates an optional symbol.
    • Expression “symbol∗” indicates a symbol repeating zero or more
    times.
    • Expression “symbol+” indicates a symbol repeating one or more
    times.
    Additional notes about the Narsese grammar:
    • A word is a string of characters of a given alphabet.
    • A truth-value or desire-value is a pair of numbers from
    [0, 1] × (0, 1), though in communication between the system and
    its environment, it can be replaced by amounts of evidence or
    frequency interval, as well as with default values.
    • Most prefix operators in compound term and compound statement
    can also be used in infix form.
    217

    218 Non-Axiomatic Logic: A Model of Intelligent Reasoning
    Table A.1. The complete grammar of Narsese.
    sentence ::= judgment|goal|question
    judgment ::= [tense]statement. truth-value
    goal ::= statement! desire-value
    question ::= [tense]statement? | statement¿
    statement ::= (term copula term) | term
    | (¬ statement)
    | (∧ statement statement
    +)
    | (∨ statement statement
    +)
    | ( , statement statement
    +)
    | ( ; statement statement
    +)
    | (⇑word term
    ∗)
    copula ::= →|↔|⇒|⇔
    | ◦→ | →◦ | ◦→◦
    | /⇒ | \⇒ | |⇒ | /⇔ | |⇔
    tense ::= /⇒ | \⇒ | |⇒
    term ::= word|variable|statement
    | {term
    +} | [term
    +]
    | (∩ term term
    +)
    | (∪ term term
    +)
    | (− term term)
    | ( term term)
    | (× term term
    +)
    | (/ term term
    ∗  term
    ∗)
    | (\ term term
    ∗  term
    ∗)
    variable ::= independent-variable
    |dependent-variable
    |query-variable
    independent-variable ::= #word
    dependent-variable ::= # [word(independent-variable
    ∗)]
    query-variable ::= ? [word]
    • In an (extensional or intensional) image, the two term∗ cannot
    be both empty.
    • There are additional restrictions on the meaningful usage of
    variable introduced in NAL-6.
    The symbols appeared in Narsese grammar are listed in
    Table A.2.
                             
    Appendix A 219
    Table A.2. The symbols in Narsese grammar.
    Type Symbol Name Layer
    Primary copula → Inheritance NAL-1
    ↔ Similarity NAL-2
    ⇒ Implication NAL-5
    ⇔ Equivalence NAL-5
    Secondary copula ◦→ Instance NAL-2
    →◦ Property NAL-2
    ◦→◦ Instance-property NAL-2
    /⇒ Predictive implication NAL-7
    \⇒ Retrospective implication NAL-7
    |⇒ Concurrent implication NAL-7
    /⇔ Predictive equivalence NAL-7
    |⇔ Concurrent equivalence NAL-7
    Tense /⇒ Future NAL-7
    \⇒ Past NAL-7
    |⇒ Present NAL-7
    Term connector {} Extensional set NAL-2
    [ ] Intensional set NAL-2
    ∩ Extensional intersection NAL-3
    ∪ Intensional intersection NAL-3
    − Extensional difference NAL-3
    Intensional difference NAL-3
    × Product NAL-4
    / Extensional image NAL-4
    \ Intensional image NAL-4
     Image place-holder NAL-4
    Statement connector ¬ Negation NAL-5
    ∧ Conjunction NAL-5
    ∨ Disjunction NAL-5
    , Sequential conjunction NAL-7
    ; Parallel conjunction NAL-7
    Term prefix # Variable in judgment NAL-6
    ? Variable in question NAL-6
    ⇑ Operator NAL-8
    Punctuation . Judgment NAL-8
    ! Goal NAL-8
    ? Question (on truth-value) NAL-8
    ¿ Query (on desire-value) NAL-8
    This page intentionally left blank
    APPENDIX B
    NAL INFERENCE RULES
    The inference rules of NAL are classified into several categories
    according to their syntactic features.
    (1) Local inference rules: Each of these rules directly processes a
    new inference task according to the available information stored
    locally in the concept representing the content of the task. These
    rules are applied before the other rules are attempted on the
    task.
    (1.1) Revision. When the task is a judgment and contains
    neither tense nor dependent variable, the system matches
    it with the existing judgments on the same statement. If a
    matching judgment is found and the two judgments have
    distinct evidential bases, the revision rule is applied to
    produce a new judgment with the same statement and a
    truth-value calculated by Frev. When the task is a goal,
    the same revision process is done to its desire-value.
    (1.2) Choice. When the task is a question (or a goal), the
    system matches it with the existing judgments on the
    same statement to find candidate answers (or solutions).
    If the candidates all contain the same statement, the one
    with the highest confidence is chosen; if the candidates
    suggest different instantiations to the variable(s) in the
    task, the one with high expectation e and low complexity
    n is chosen, using the ranking formula e/nr (with r = 1
    as the default).
    221
    222 Non-Axiomatic Logic: A Model of Intelligent Reasoning
    (1.3) Decision. A candidate goal is accepted by the system
    as an active goal when its expected desirability d and
    expected plausibility p satisfy condition p(d − 0.5) > t,
    where t is a positive threshold.
    (2) Two-premise inference rules: each of these rules takes two
    judgments J1 and J2 as premises, and derives a judgment J as
    conclusion, with a truth-value calculated by a function.
    (2.1) First-order syllogism, in Table B.1, are defined on
    copulas inheritance and similarity.
    (2.2) Higher-order syllogism can be obtained by replacing
    the copulas inheritance and similarity in Table B.1 with
    implication and equivalence, respectively.
    (2.3) Conditional syllogism, in Table B.2, are based on the
    nature of conditional statements.
    (2.4) Composition rules, in Table B.3, introduce new compounds in the conclusion.
    (2.5) Decomposition rules are the opposite of the composition rules. Each decomposition rule comes from a highlevel theorem of the form (S1 ∧ S2) =⇒ S (in Table B.4)
    where S1 is a statement about a compound, S2 is a
    statement about a component of the compound, while S
    is the statement about the other component.
    Table B.1. The first-order syllogistic rules.
    J2\J1 M → P f1, c1 P → M f1, c1 M ↔ P f1, c1
    S → P Fded S → P Fabd S → P F
    ana
    S → M f2, c2 P → S F
    exe P → S F
    abd
    S ↔ P F
    com
    S → P Find S → P Fexe
    M → S f2, c2 P → S F
    ind P → S F
    ded P → S F
    ana
    S ↔ P Fcom
    S → P Fana
    S ↔ M f2, c2 P → S Fana
    S ↔ P Fres

    Appendix B 223
    Table B.2. The conditional syllogistic rules.
    J1 f1, c1 J2 f2, c2 J F
    S S ⇔ P PFana
    S PS ⇔ P Fcom
    S ⇒ P S PFded
    P ⇒ S S PFabd
    P SS ⇒ P Find
    (C ∧ S) ⇒ P SC ⇒ P Fded
    (C ∧ S) ⇒ P C ⇒ P SFabd
    C ⇒ P S (C ∧ S) ⇒ P Find
    (C ∧ S) ⇒ P M ⇒ S (C ∧ M) ⇒ P Fded
    (C ∧ S) ⇒ P (C ∧ M) ⇒ P M ⇒ S Fabd
    (C ∧ M) ⇒ P M ⇒ S (C ∧ S) ⇒ P Find
    Table B.3. The composition rules.
    J1 f1, c1 J2 f2, c2 J F
    M → T1 M → T2 M → (T1 ∩ T2) Fint
    M → (T1 ∪ T2) Funi
    M → (T1 − T2) Fdif
    M → (T2 − T1) F
    dif
    T1 → M T2 → M (T1 ∪ T2) → M Fint
    (T1 ∩ T2) → M Funi
    (T1
    T2) → M Fdif
    (T2
    T1) → M F
    dif
    M ⇒ T1 M ⇒ T2 M ⇒ (T1 ∧ T2) Fint
    M ⇒ (T1 ∨ T2) Funi
    T1 ⇒ M T2 ⇒ M (T1 ∨ T2) ⇒ M Fint
    (T1 ∧ T2) ⇒ M Funi
    T1 T2 T1 ∧ T2 Fint
    T1 ∨ T2 Funi
    (3) One-premise inference rules: Each of these rules carries out
    inference from a judgment J1 as premise to a judgment J as
    conclusion, with a truth-value calculated by function F.
    (3.1) Immediate inference,inTableB.5,areruleswithatruthvalue function that only takes one truth-value as input.

    224 Non-Axiomatic Logic: A Model of Intelligent Reasoning
    Table B.4. The decomposition rules.
    S1 S2 S
    ¬(M → (T1 ∩ T2)) M → T1 ¬(M → T2)
    M → (T1 ∪ T2) ¬(M → T1) M → T2
    ¬(M → (T1 − T2)) M → T1 M → T2
    ¬(M → (T2 − T1)) ¬(M → T1) ¬(M → T2)
    ¬((T1 ∪ T2) → M) T1 → M ¬(T2 → M)
    (T1 ∩ T2) → M ¬(T1 → M) T2 → M
    ¬((T1
    T2) → M) T1 → M T2 → M
    ¬((T2
    T1) → M) ¬(T1 → M) ¬(T2 → M)
    ¬(T1 ∧ T2) T1 ¬T2
    T1 ∨ T2 ¬T1 T2
    Table B.5. The immediate inference
    rules.
    J1 J F
    S ¬S Fneg
    S → P P → S Fcnv
    S ⇒ P P ⇒ S Fcnv
    S ⇒ P (¬P) ⇒ (¬S) Fcnt
    Table B.6. The inheritance theorems.
    term1 → term2
    (T1 ∩ T2) T1
    T1 (T1 ∪ T2)
    (T1 − T2) T1
    T1 (T1
    T2)
    ((R/T) × T) R
    R ((R \ T) × T)
    (3.2) Structural inference is carried out according to the
    literal meaning of compound terms. When a definition
    or theorem in IL (summarized in Tables B.6–B.9) is used
    as a Narsese judgment J2 with truth value 1, 1, it can be
    used with an empirical judgment J1 to derive a conclusion
    Non-Axiomatic Logic Downloaded from www.worldscientific.com by 182.253.132.100 on 10/06/22. Re-use and distribution is strictly not permitted, except for Open Access articles. 
    Appendix B 225
    Table B.7. The similarity theorems.
    term1 ↔ term2
    ¬(¬T) T
    (∪ {T1} ··· {Tn}) {T1, ..., Tn}
    (∩ [T1] ··· [Tn]) [T1, ..., Tn]
    ({T1, ..., Tn}−{Tn}) {T1, ..., Tn−1}
    ([T1, ..., Tn]
    [Tn]) [T1, ..., Tn−1]
    ((T1 × T2) / T2) T1
    ((T1 × T2) \ T2) T1
    Table B.8. The implication theorems.
    statement1 ⇒ statement2
    S ↔ P S → P
    S ⇔ P S ⇒ P
    S1 ∧ S2 S1
    S1 S1 ∨ S2
    S → P (S ∪ M) → (P ∪ M)
    S → P (S ∩ M) → (P ∩ M)
    S ↔ P (S ∪ M) ↔ (P ∪ M)
    S ↔ P (S ∩ M) ↔ (P ∩ M)
    S ⇒ P (S ∨ M) ⇒ (P ∨ M)
    S ⇒ P (S ∧ M) ⇒ (P ∧ M)
    S ⇔ P (S ∨ M) ⇔ (P ∨ M)
    S ⇔ P (S ∧ M) ⇔ (P ∧ M)
    S → P (S − M) → (P − M)
    S → P (M − P) → (M − S)
    S → P (S
    M) → (P
    M)
    S → P (M
    P) → (M
    S)
    S ↔ P (S − M) ↔ (P − M)
    S ↔ P (M − P) ↔ (M − S)
    S ↔ P (S
    M) ↔ (P
    M)
    S ↔ P (M
    P) ↔ (M
    S)
    M → (T1 − T2) ¬(M → T2)
    (T1
    T2) → M ¬(T2 → M)
    S → P (S/M) → (P /M)
    S → P (S \ M) → (P \ M)
    S → P (M /P) → (M/S)
    S → P (M \ P) → (M \ S)
    226 Non-Axiomatic Logic: A Model of Intelligent Reasoning
    Table B.9. The equivalence theorems.
    statement1 ⇔ statement2
    S ↔ P (S → P) ∧ (P → S)
    S ⇔ P (S ⇒ P) ∧ (P ⇒ S)
    S ↔ P {S}↔{P}
    S ↔ P [S] ↔ [P]
    S → {P} S ↔ {P}
    [S] → P [S] ↔ P
    (S1 × S2) → (P1 × P2) (S1 → P1) ∧ (S2 → P2)
    (S1 × S2) ↔ (P1 × P2) (S1 ↔ P1) ∧ (S2 ↔ P2)
    S → P (M × S) → (M × P)
    S → P (S × M) → (P × M)
    S ↔ P (M × S) ↔ (M × P)
    S ↔ P (S × M) ↔ (P × M)
    (× T1 T2) → R T1 → (/ R  T2)
    (× T1 T2) → R T2 → (/RT1 )
    R → (× T1 T2) (\ R  T2) → T1
    R → (× T1 T2) (\ R T1 ) → T2
    S1 ⇒ (S2 ⇒ S3) (S1 ∧ S2) ⇒ S3
    ¬(S1 ∧ S2) (¬S1) ∨ (¬S2)
    ¬(S1 ∨ S2) (¬S1) ∧ (¬S2)
    S1 ⇔ S2 (¬S1) ⇔ (¬S2)
    J by a strong syllogistic rule. Since J2 is not explicitly
    represented, this rule effectively derives J from a single
    premise J1.
    (4) Meta-level rules: Each of these rules specifies how to use the
    other rules defined above for additional functions.
    (4.1) Question derivation. A question Q and a judgment J
    produce a derived question Q
    , if and only if the answer
    to Q
    , call it J
    , can be used with J to derive an answer to
    Q by a two-premise inference rule; a question Q by itself
    produces a derived question Q
    , if and only if the answer
    to Q
    , call it J
    , can be used to derive an answer to Q by
    a one-premise inference rule.
    (4.2) Goal derivation. A goal G and a judgment J produce
    a derived goal G
    , if and only if the solution to G
    , call
    it J
    , can be used with J to derive a solution to G by a
    Appendix B 227
    two-premise inference rule; a goal G by itself produces a
    derived goal G
    , if and only if the solution to G
    , call it
    J
    , can be used to derive a solution to G by a one-premise
    inference rule. In both cases, the desire-value of G is
    derived as the truth-value of G ⇒ D from the desire-value
    of G, as the truth-value of G ⇒ D, as well as the truthvalue of J (if it is involved). As mentioned previously, a
    derived goal needs to go through the decision-making rule
    to become an actual goal.
    (4.3) Variable substitution. All occurrences of an independent variable term in a statement can be substituted by
    another term (constant or variable); all occurrences of a
    term (constant or variable) in a statement can be substituted by a dependent variable term. The reverse cases
    of these substitution are limited to the cases discussed in
    NAL-6. A query variable in a question can be substituted
    by a constant term in a judgment.
    (4.4) Temporal inference. Temporal inference is carried out
    by processing the logical factor and the temporal factor
    in the premises in parallel. First, temporal variants of
    IL rules are obtained by turning some statements in the
    premises into events by adding temporal order among
    them, and the conclusion must keep the same temporal
    information. Then these rules are extended into strong
    NAL rules by using the same truth-value function as in
    the lower layers. The rules of weak inference are formed
    as the reverse of the strong rules as in the lower layers.
    This page intentionally left blank
    APPENDIX C
    NAL TRUTH-VALUE FUNCTIONS
    The relations among the three forms of uncertainty measurements are
    summarized in Table C.1, which can be extended to include w− =
    w − w+ and i = u − l.
    For independent extended-Boolean variables in [0, 1], the
    extended Boolean operators are defined in Table C.2.
    All truth-value functions are summarized in Table C.3, in their
    simplest form, so different types of uncertainty measurements are
    mixed. The functions are classified according to the type of inference.
    Table C.1. The relations among uncertainty measurements.
    to\from {w+, w} f, c [ l, u ] (and i)
    {w+, w} w+ = k × f × c / (1 − c) w+ = k × l/i
    w = k × c / (1 − c) w = k × (1 − i) / i
    f, c f = w+ /w f = l / (1 − i)
    c = w / (w + k) c = 1 − i
    [l, u] l = w+ / (w + k) l = f × c
    u = (w+ + k) / (w + k) u = 1 − c × (1 − f)
    Table C.2. The extended Boolean operators.
    not(x)=1 − x
    and(x1,...,xn) = x1 ×···× xn
    or(x1,...,xn)=1 − (1 − x1) ×···× (1 − xn)
    229
    Non-Axiomatic Logic Downloaded from www.worldscientific.com by 182.253.132.100 on 10/06/22. Re-use and distribution is strictly not permitted, except for Open Access articles.  
    230 Non-Axiomatic Logic: A Model of Intelligent Reasoning
    Table C.3. The truth-value functions of NAL.
    Type Inference Name Function
    Local inference Revision Frev w+ = w+
    1 + w+
    2
    w− = w−
    1 + w−
    2
    Expectation Fexp e = c(f − 0.5) + 0.5
    Decision Fdec g = p(d − 0.5)
    Immediate inference Negation Fneg w+ = w−
    1
    w− = w+
    1
    Conversion Fcnv w+ = and(f1, c1)
    w− = 0
    Contraposition Fcnt w+ = 0
    w− = and((not(f1), c1)
    Strong syllogism Deduction Fded f = and(f1, f2)
    c = and(f1, f2, c1, c2)
    Analogy Fana f = and(f1, f2)
    c = and(f2, c1, c2)
    Resemblance Fres f = and(f1, f2)
    c = and(or(f1, f2), c1, c2)
    Weak syllogism Abduction Fabd w+ = and(f1, f2, c1, c2)
    w = and(f1, c1, c2)
    Induction Find w+ = and(f1, f2, c1, c2)
    w = and(f2, c1, c2)
    Exemplification Fexe w+ = and(f1, f2, c1, c2)
    w = and(f1, f2, c1, c2)
    Comparison Fcom w+ = and(f1, f2, c1, c2)
    w = and(or(f1, f2), c1, c2)
    Term composition Intersection Fint f = and(f1, f2)
    c = and(c1, c2)
    Union Funi f = or(f1, f2)
    c = and(c1, c2)
    Difference Fdif f = and(f1, not(f2))
    c = and(c1, c2)
    APPENDIX D
    PROOFS OF THEOREMS
    Except explicitly specified otherwise, in the following letters S, P, M,
    and T each represents an arbitrary term in the system’s vocabulary,
    and the Tis are different terms.
    Theorem 2.1.
    By definition, the copula ‘→’ is defined among terms, and is reflexive.
    Formally, it means T → T.
    Theorem 2.2.
    By definition, the copula ‘→’ is defined among terms, and is
    transitive. Formally, it means (S → M) ∧ (M → P) =⇒ (S → P).
    Theorem 2.3.
    By definition, T E = {x |(x ∈ VK) ∧ (x → T)}. Since T → T is always
    true, so as far as T ∈ VK, T ∈ T E. If T is not in VK, no x in VK can
    make x → T true, so T E = {}. The TI part is parallel to the above.
    Theorem 2.4.
    If both S and P are in VK, then SE is not empty. For any T in SE,
    by the definition of extension, T → S is true. Since S → P and ‘→’ is
    transitive, T → P is true, which means T is also in P E, and therefore
    SE ⊆ P E. The other way around, from S ∈ SE and SE ⊆ P E, it
    follows that S ∈ P E. Given the definition of P E, it means S → P.
    The intensional part is parallel to the above.
    231
    232 Non-Axiomatic Logic: A Model of Intelligent Reasoning
    Theorem 2.5.
    (SE = P E) ⇐⇒ (SE ⊆ P E) ∧ (P E ⊆ SE)
    ⇐⇒ (PI ⊆ SI ) ∧ (SI ⊆ PI )
    ⇐⇒ (SI = PI ).
    Theorem 6.1.
    Similarity is a reflexive copula, because T ↔T is defined as (T →T)∧
    (T → T), which is a conjunction of two true propositions.
    Similarity is a symmetric copula, because S ↔ P is defined as
    (S → P) ∧ (P → S), which is equivalent to P ↔ S.
    Similarity is a transitive copula, because (S ↔ M)∧(M ↔ P) is
    equivalent to (S → M)∧(M → S)∧(M → P)∧(P → M). Given the
    transitivity of inheritance, (S → P)∧(P → S) follows, and therefore
    S ↔ P.
    Theorem 6.2.
    By definition, S ↔ P is (S → P) ∧ (P → S), therefore it implies
    S → P.
    Theorem 6.3.
    By definition, S ↔ P is (S → P)∧(P → S). Given the definitions of
    extension and intension, it is equivalent to (S ∈ P E)∧(P ∈ SI )∧(P ∈
    SE) ∧ (S ∈ PI ), which is the same as (S ∈ (P E ∩ PI )) and (P ∈
    (SE ∩ SI )).
    Theorem 6.4.
    By definition, S ↔ P is (S → P) ∧ (P → S). Given Theorem 2.4,
    it is equivalent to (SE ⊆ P E) ∧ (P E ⊆ SE), which is the same as
    SE = P E. The intensional part is parallel to the above.
    Theorem 6.5.
    By definition, any M in {T}E is identical to {T}, which implies
    {T} → M, so M is also in {T}I .
    Appendix D 233
    Theorem 6.6.
    By definition, S ◦→ M is equivalent to {S} → M. Given the
    transitivity of inheritance, it and M → P imply {S} → P, that
    is, S ◦→ P.
    Theorem 6.7.
    The proof of this theorem is parallel to the proof of Theorem 6.5.
    Theorem 6.8.
    The proof of this theorem is parallel to the proof of Theorem 6.6.
    Theorem 6.9.
    By the definitions of the derived copulas, all the three statements,
    S ◦→◦ P, {S} →◦ P, and S ◦→ [P], can be rewritten as {S} → [P].
    Theorem 7.1.
    This theorem covers two special cases of Definition 7.5.
    Theorem 7.2.
    (S ↔ P) ⇐⇒ ({S}↔{P})
    ⇐⇒ ({S}→{P})
    ⇐⇒ (S ◦→ {P}).
    (S ↔ P) ⇐⇒ ([S] →◦ P) can be proved in a parallel way.
    Theorem 7.3.
    Extension:
    (M ∈ (T1 ∩ T2)E) ⇐⇒ (M → (T1 ∩ T2))
    ⇐⇒ ((M → T1) ∧ (M → T2))
    ⇐⇒ ((M ∈ T E
    1 ) ∧ (M ∈ T E
    2 ))
    ⇐⇒ (M ∈ (T E
    1 ∩ T E
    2 )).
    234 Non-Axiomatic Logic: A Model of Intelligent Reasoning
    Intension:
    (M ∈ (T1 ∩ T2)I ) ⇐⇒ ((T1 ∩ T2) → M)
    ⇐⇒ ((T1 → M) ∨ (T2 → M))
    ⇐⇒ ((M ∈ TI
    1 ) ∨ (M ∈ TI
    2 ))
    ⇐⇒ (M ∈ (TI
    1 ∪ TI
    2 )).
    In both cases, the compound term is in its own extension and
    intension, according to Theorem 2.3.
    Theorem 7.4.
    The proof of this theorem is parallel to the proof of Theorem 7.3.
    Theorem 7.5.
    In the definitions of extensional intersection and intensional intersection, the order of the two components can be switched.
    Theorem 7.6.
    According to Theorem 7.3, (T1 ∩ T2)E = (T E
    1 ∩ T E
    2 ), so (T1 ∩ T2)E ⊆
    T E
    1 . According to Theorem 2.4, it means (T1 ∩T2) → T1. The second
    conclusion can be proved in a parallel way.
    Theorem 7.7.
    According to Theorem 7.3, (T ∩ T)E = (T E ∩ T E) = T E. According
    to Theorem 6.4, it means (T ∩ T) ↔ T. The second conclusion can
    be proved in a parallel way.
    Theorem 7.8.
    According to propositional logic, implication of the definition of
    extensional intersection ((M →T1)∧ (M →T2))=⇒(M →(T1 ∩ T2))
    can be rewritten equivalently into ((M → T1) ∧ ¬(M → (T1 ∩
    T2)))=⇒ ¬(M → T2), and ((T1 ∩ T2) → M)=⇒((T1 → M) ∨ (T2 →
    M)) into (¬(T1 → M) ∧ (T1 ∩ T2) → M)=⇒(T2 → M). The
    conclusions on intensional intersection can be proved in parallel.
    Appendix D 235
    Theorem 7.9.
    (S → P) =⇒ (SE ⊆ P E) (Theorem 2.4)
    =⇒ ((SE ∩ ME) ⊆ (P E ∩ ME)) (set theory)
    =⇒ ((S ∩ M)E ⊆ (P ∩ M)E) (Theorem 7.3)
    =⇒ ((S ∩ M) → (P ∩ M)) (Theorem 2.4).
    The other three conclusions can be proved in a parallel way.
    Theorem 7.10.
    (M ∈ (T1 − T2)E) ⇐⇒ (M → (T1 − T2))
    ⇐⇒ ((M → T1) ∧ ¬(M → T2))
    ⇐⇒ ((M ∈ T E
    1 ) ∧ ¬(M ∈ T E
    2 ))
    ⇐⇒ (M ∈ (T E
    1 − T E
    2 )).
    (M ∈ (T1 − T2)I ) ⇐⇒ ((T1 − T2) → M)
    ⇐⇒ (T1 → M)
    ⇐⇒ (M ∈ TI
    1 ).
    Theorem 7.11.
    The proof of this theorem is parallel to the proof of Theorem 7.10.
    Theorem 7.12.
    This theorem corresponds to the special cases of the definitions of
    extensional difference (when x is (T1−T2)) and intensional difference
    (when x is (T1  T2)), respectively.
    Theorem 7.13.
    This theorem corresponds to the special cases of the definitions of
    extensional difference (when M is (T1−T2)) and intensional difference
    (when M is (T1  T2)), respectively.
    Theorem 7.14.
    According to propositional logic, implication of the definition of
    extensional difference ((M →T1)∧ ¬(M →T2))=⇒(M →(T1 − T2))
    can be rewritten equivalently into ((M → T1)∧ ¬(M →(T1 −
    236 Non-Axiomatic Logic: A Model of Intelligent Reasoning
    T2)))=⇒(M →T2), as well as (¬(M → T2)∧¬(M → (T1−T2))) =⇒
    ¬(M → T1). The conclusions on intensional difference can be proved
    in parallel.
    Theorem 7.15.
    The proof of this theorem is parallel to the proof of Theorem 7.9,
    with the role of Theorem 7.3 being played by Theorem 7.10.
    Theorem 7.16.
    ((T ∩ M) ∪ (T − M))E = (T ∩ M)E ∪ (T − M)E
    = (T E ∩ ME) ∪ (T E − ME)
    = T E.
    ((T ∩ M) ∪ (T − M))I = (T ∩ M)I ∩ (T − M)I
    = (TI ∪ MI ) ∩ (TI ∪ {(T − M)})
    = TI .
    According to Theorem 6.4, T ↔ ((T ∩ M) ∪ (T − M)).
    The other result can be proved in parallel.
    Theorem 7.17.
    For any term x,
    x → ((T ∪ M) − M) =⇒ (x → (T ∪ M)) ∧ ¬(x → M)
    =⇒ ((x → T) ∨ (x → M)) ∧ ¬(x → M) =⇒ x → T.
    x → T =⇒ (x → T) ∧ (¬(x → M) ∨ (x → M))
    =⇒ ((x → T) ∧ ¬(x → M)) ∨ ((x → T) ∧ (x → M))
    =⇒ (x → (T − M)) ∨ (x → M) =⇒ x → ((T − M) ∪ M).
    The other results can be proved in parallel.
    Theorem 7.18.
    (M ◦→ {T1, ..., Tn})
    ⇐⇒ ({M}→{T1, ..., Tn})
    ⇐⇒ ({M} → ({T1} ∪ ... ∪ {Tn}))
    ⇐⇒ (({M}→{T1}) ∨ ... ∨ ({M}→{Tn}))
    ⇐⇒ ((M ↔ T1) ∨ ... ∨ (M ↔ Tn)).
    The conclusion on intensional set can be proved in parallel.
    Appendix D 237
    Theorem 7.19.
    ({M} → ({T1, ..., Tn}−{Tn}))
    ⇐⇒ (({M}→{T1, ..., Tn}) ∧ ¬({M}→{Tn}))
    ⇐⇒ (((M ↔ T1) ∨ ... ∨ (M ↔ Tn)) ∧ ¬(M ↔ Tn))
    ⇐⇒ ((M ↔ T1) ∨ ... ∨ (M ↔ Tn−1))
    ⇐⇒ ({M}→{T1, ..., Tn−1}).
    Since ({T1, ..., Tn}−{Tn}) and {T1, ..., Tn−1} are extensional
    sets defined by the same instances, the two terms are identical. The
    conclusion on intensional set can be proved in parallel.
    Theorem 8.1.
    ((S1 × S2) ↔ (P1 × P2))
    ⇐⇒ (((S1 × S2) → (P1 × P2)) ∧ ((P1 × P2) → (S1 × S2)))
    ⇐⇒ ((S1 → P1) ∧ (S2 → P2) ∧ (P1 → S1) ∧ (P2 → S2))
    ⇐⇒ ((S1 ↔ P1) ∧ (S2 ↔ P2)).
    Theorem 8.2.
    This theorem is implied by the definition of product and tautology
    M → M.
    Theorem 8.3.
    ((x ∈ T E
    1 ) ∧ (y ∈ T E
    2 )) =⇒ ((x → T1) ∧ (y → T2))
    =⇒ ((x × y) → (T1 × T2))
    =⇒ ((x × y) ∈ (T1 × T2)E)).
    The conclusion on intension can be proved in parallel.
    Theorem 8.4.
    (((×, S1, S2) → (×, P1, P2)) ∧ ((×, S1, S3) → (×, P1, P3)))
    ⇐⇒ ((S1 → P1) ∧ (S2 → P2) ∧ (S3 → P3))
    ⇐⇒ ((×, S1, S2, S3) → (×, P1, P2, P3)).
    Theorem 8.5.
    ((T1 × T2) → (T1 × T2)) =⇒ (T1 → ((T1 × T2) / T2))
    (x → ((T1 × T2) / T2)) =⇒ ((x × T2) → (T1 × T2))
    =⇒ (x → T1).
    The conclusion on intensional image can be proved in parallel.
    238 Non-Axiomatic Logic: A Model of Intelligent Reasoning
    Theorem 8.6.
    ((R/T) → (R/T)) =⇒ (((R/T) × T) → R).
    The conclusion on intensional image can be proved in parallel.
    Theorem 8.7.
    (((S/M) × M) → S) ∧ (S → P)
    =⇒ (((S/M) × M) → P)
    =⇒ ((S/M) → (P /M))
    (((M /P) × P) → M) ∧ (S → P)
    =⇒ (P → (/ M (M /P) )) ∧ (S → P)
    =⇒ (S → (/ M (M /P) ))
    =⇒ (M /P) → (M /S).
    The conclusion on intensional image can be proved in parallel.
    Theorem 9.1.
    Since {S}  S, S ⇒ S is true. If S ⇒ M and M ⇒ P are both true,
    then {S}  M and {M}  P, which means {S}  P, with M as an
    intermediate result. Therefore S ⇒ P is true.
    Theorem 9.2.
    The proof of this theorem is parallel to the proof of Theorem 2.4.
    Theorem 9.3.
    The result directly follows from Definition 9.6, with x substituted by
    S1 ∧ S2 and S1 ∨ S2, respectively.
    Theorem 9.4.
    At the meta-level, (S1 ⇒ (S2 ⇒ S3)) means ({S1}  (S2 ⇒ S3)),
    therefore ({S1, S2}  S3). Rewritten in object-level, it is ((S1 ∧
    S2)⇒ S3).
    Appendix D 239
    Theorem 9.5.
    This theorem can be proved using truth table, as in propositional
    logic.
    Theorem 9.6.
    This theorem can be proved using truth table, as in propositional
    logic.
    Theorem 9.7.
    This theorem can be proved using truth table, as in propositional
    logic.
    Theorem 9.8.
    (S1 ⇔ S2) if and only if S1 and S2 derive each other, which means
    (¬S1) and (¬S2) also derive each other, that is, ((¬S1) ⇔ (¬S2)).
    Please note that it is not enough if S1 and S2 have the same truthvalue, or the same amount of evidence.
*/

/**
 * Nars Reasoner support classes
 */

