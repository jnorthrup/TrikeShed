package borg.trikeshed.placeholder.nars

/** a master enum of named elements to use as symbols following*/

enum class Element(val parent: Element? = null, val cf: symbolConfix?=null) {
        task,desire , word ,

    //copulas
    copula, inheritance(copula, symbolConfix.of('-', '>')), similarity(copula, symbolConfix.of('<', '>')), instance(copula,
        symbolConfix.of('{', '-')
    ),
    property(copula, symbolConfix.of('-', ']')), instance_property(copula, symbolConfix.of('{', '-')), implication(copula,
        symbolConfix.of('=', '>')
    ),
    predictive_implication(copula, symbolConfix.of('=', '/')), concurrent_implication(copula, symbolConfix.of('=', '|')),
    retrospective_implication(copula, symbolConfix.of('=', '\\')), equivalence(copula, symbolConfix.of('<', '>')),
    predictive_equivalence(copula, symbolConfix.of('<', '/')), concurrent_equivalence(copula, symbolConfix.of('<', '|')),

    //terms
    term, atomic_term(term), compound_term(term), statement(term),

    //compound terms
    op_ext_set(compound_term, symbolConfix.of('{', '}')), op_int_set(compound_term, symbolConfix.of('[', ']')), op_negation(compound_term ),
    op_int_image(compound_term ), op_ext_image(compound_term ), op_multi(compound_term), op_single(compound_term),

    //variables
    variable, independent_variable(variable  ), dependent_variable(variable ),
    query_variable(variable  ),

    //tense
    tense, future_tense(tense, symbolConfix.of(':', '/')), present_tense(tense, symbolConfix.of(':', '|')), past_tense(tense,
        symbolConfix.of(':', '\\')
    ),

    //desire
    truth(desire),
    budget,

    //priority
    priority(budget ), durability(budget  ), quality(budget ),

    //frequency
    frequency(truth ), confidence(truth ),
;
    //create  maps of all elements by begin, end if avaiilable
    companion object {
        val beginnings = Element.values().filter { it.cf != null }.associateBy { it.cf!!.begin }
        val _endings = Element.values().filter { it.cf != null }.associateBy { it.cf!!.end }
        // create  a mapping of child relations for each element to its parent
        val parentMap = Element.values().associateBy { it }.mapValues { it.value.parent }.filterValues { it != null }.mapValues { it.value!! }
        //create a mapping of each pa=rent to its children
        val childMap = parentMap.values.toSet().associateWith { parent -> Element.values().filter { it.parent == parent } }
        //create a hierarchical rank mapping for each element by ascent count for each node in the parent Map
        val rankMap = Element.values().associateWith {
            var c = 0;
            var p = it.parent
            while (p != null) {
                c++
                p = p.parent
            }
            c
        }
    }
}