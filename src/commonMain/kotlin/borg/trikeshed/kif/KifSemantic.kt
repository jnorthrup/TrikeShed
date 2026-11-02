package borg.trikeshed.kif

/** Scoped source IR, not an MLIR dialect or a quantified inference engine. */
sealed class KifTerm {
    data class Constant(val token: String) : KifTerm()
    data class Literal(val spelling: String) : KifTerm()
    data class Free(val name: String) : KifTerm()
    data class Bound(val binder: Int) : KifTerm()
    data class Function(val name: String, val arguments: List<KifTerm>) : KifTerm()
    /** Quoted syntax has no variable capture or executable interpretation here. */
    data class Quote(val syntax: KifExpr) : KifTerm()
    data class Unsupported(val syntax: KifExpr) : KifTerm()
}

sealed class KifFormula {
    data class Predicate(val name: String, val arguments: List<KifTerm>) : KifFormula()
    data class Not(val body: KifFormula) : KifFormula()
    data class And(val bodies: List<KifFormula>) : KifFormula()
    data class Or(val bodies: List<KifFormula>) : KifFormula()
    data class Implies(val antecedent: KifFormula, val consequent: KifFormula) : KifFormula()
    data class Equivalent(val left: KifFormula, val right: KifFormula) : KifFormula()
    data class ForAll(val binders: List<Int>, val body: KifFormula) : KifFormula()
    data class Exists(val binders: List<Int>, val body: KifFormula) : KifFormula()
    /** Modality, quotation in formula position and unknown higher-order forms stay intact. */
    data class Unsupported(val syntax: KifExpr) : KifFormula()
}

data class KifDecodeLimits(val maxChars: Int = 65536, val maxDepth: Int = 64, val maxNodes: Int = 8192) {
    init { require(maxChars in 1..1048576 && maxDepth in 1..128 && maxNodes in 1..65536) }
}

data class KifDecoded(
    val syntax: KifExpr,
    val formula: KifFormula,
    val freeVariables: Set<String>,
    val unsupportedForms: List<KifExpr>,
) {
    /** Binder IDs follow lexical traversal; source binder spellings do not determine identity. */
    fun alphaEquivalent(other: KifDecoded): Boolean = formula == other.formula
}

object KifSemantic {
    fun decode(text: String, limits: KifDecodeLimits = KifDecodeLimits()): KifDecoded {
        require(text.length <= limits.maxChars) { "KIF input exceeds character bound" }
        // Check before invoking the recursive syntax parser, respecting comments and escaped strings.
        var depth = 0
        var quoted = false
        var escaped = false
        var comment = false
        var quotePrefixes = 0
        var activeQuotes = 0
        val quoteCosts = mutableListOf<Int>()
        for (c in text) {
            if (!quoted && !comment) {
                if (c == '\'') {
                    quotePrefixes++
                    require(quotePrefixes + activeQuotes + depth <= limits.maxDepth) { "KIF quotation nesting exceeds bound" }
                } else if (!c.isWhitespace() && c != ';' && c != '(' && c != ')') quotePrefixes = 0
            }
            when {
                comment -> if (c == '\n' || c == '\r') comment = false
                quoted -> when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> quoted = false
                }
                c == ';' -> comment = true
                c == '"' -> quoted = true
                c == '(' -> {
                    depth++
                    quoteCosts.add(quotePrefixes)
                    activeQuotes += quotePrefixes
                    quotePrefixes = 0
                    require(depth + activeQuotes <= limits.maxDepth) { "KIF nesting exceeds bound" }
                }
                c == ')' -> {
                    depth--
                    require(depth >= 0) { "Unexpected closing parenthesis" }
                    activeQuotes -= quoteCosts.removeAt(quoteCosts.lastIndex)
                    quotePrefixes = 0
                }
            }
        }
        require(!quoted && depth == 0) { "Truncated KIF expression" }
        val forms = KifExpr.parseAll(text, strict = true)
        require(forms.size == 1) { "Exactly one KIF formula required" }
        val syntax = forms.single()
        var nodes = 0
        fun count(e: KifExpr, level: Int) {
            require(++nodes <= limits.maxNodes && level <= limits.maxDepth) { "KIF syntax exceeds bound" }
            when (e) {
                is KifExpr.ListExpr -> e.elements.forEach { count(it, level + 1) }
                is KifExpr.Quoted -> count(e.expr, level + 1)
                else -> Unit
            }
        }
        count(syntax, 0)
<<<<<<< HEAD
=======
        fun variableNames(e: KifExpr) {
            when (e) {
                is KifExpr.Var -> require(e.name.length > 1) { "KIF variable requires a name after ?" }
                is KifExpr.ListExpr -> e.elements.forEach(::variableNames)
                is KifExpr.Quoted -> Unit // Quotation is retained syntax, not an active binding.
                else -> Unit
            }
        }
        variableNames(syntax)
>>>>>>> origin/codex/kif-activation-20261102
        return Decoder().decode(syntax)
    }

    private class Decoder {
        var nextBinder = 0
        val free = linkedSetOf<String>()
        val unsupported = mutableListOf<KifExpr>()

        fun decode(syntax: KifExpr): KifDecoded {
            val formula = formula(syntax, emptyMap())
            return KifDecoded(syntax, formula, free.toSet(), unsupported.toList())
        }

        private fun atom(e: KifExpr): String? = (e as? KifExpr.Atom)?.token
        private val number = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
        private fun constant(s: String): Boolean = !number.matches(s) && s.isNotEmpty() && !s.startsWith('"') &&
            !s.startsWith('@') && !s.startsWith('?')

        private fun term(e: KifExpr, scope: Map<String, Int>): KifTerm = when (e) {
            is KifExpr.Var -> scope[e.name]?.let { KifTerm.Bound(it) }
                ?: KifTerm.Free(e.name).also { free.add(e.name) }
            is KifExpr.Atom -> when {
                e.token.startsWith('"') || number.matches(e.token) -> KifTerm.Literal(e.token)
                constant(e.token) -> KifTerm.Constant(e.token)
                else -> KifTerm.Unsupported(e).also { unsupported.add(e) }
            }
            is KifExpr.Quoted -> KifTerm.Quote(e.expr)
            is KifExpr.ListExpr -> {
                val head = e.elements.firstOrNull()?.let(::atom)
                if (head != null && constant(head)) KifTerm.Function(head, e.elements.drop(1).map { term(it, scope) })
                else KifTerm.Unsupported(e).also { unsupported.add(e) }
            }
        }

        private fun formula(e: KifExpr, scope: Map<String, Int>): KifFormula {
            val list = e as? KifExpr.ListExpr
            val head = list?.elements?.firstOrNull()?.let(::atom)
            if (list == null || head == null || !constant(head)) return unknown(e, scope)
            val args = list.elements.drop(1)
            fun unary(): KifFormula { require(args.size == 1) { "$head requires one formula" }; return formula(args[0], scope) }
            fun binary(): Pair<KifFormula, KifFormula> {
                require(args.size == 2) { "$head requires two formulas" }
                return formula(args[0], scope) to formula(args[1], scope)
            }
            return when (head) {
                "not" -> KifFormula.Not(unary())
                "and" -> { require(args.size >= 2); KifFormula.And(args.map { formula(it, scope) }) }
                "or" -> { require(args.size >= 2); KifFormula.Or(args.map { formula(it, scope) }) }
                "=>", "implies" -> binary().let { KifFormula.Implies(it.first, it.second) }
                "<=>", "iff", "equivalence" -> binary().let { KifFormula.Equivalent(it.first, it.second) }
                "forall", "exists" -> {
                    require(args.size == 2) { "$head requires binders and body" }
                    val variables = (args[0] as? KifExpr.ListExpr)?.elements
                        ?: throw IllegalArgumentException("Quantifier requires variable list")
<<<<<<< HEAD
=======
                    // SUO-KIF row binders are valid syntax outside this scoped scalar fragment.
                    // Preserve the entire quantified form; do not erase the binder or decode its body as facts.
                    if (variables.any { it is KifExpr.Atom && it.token.startsWith('@') }) return unknown(e, scope)
>>>>>>> origin/codex/kif-activation-20261102
                    require(variables.isNotEmpty() && variables.all { it is KifExpr.Var }) { "Invalid quantified variables" }
                    val names = variables.map { (it as KifExpr.Var).name }
                    require(names.distinct().size == names.size) { "Duplicate binder in one scope" }
                    val bindings = names.associateWith { nextBinder++ }
                    val body = formula(args[1], scope + bindings)
                    if (head == "forall") KifFormula.ForAll(bindings.values.toList(), body)
                    else KifFormula.Exists(bindings.values.toList(), body)
                }
                // Known propositional attitudes/modality cannot be treated as ordinary function arguments.
                "modalAttribute", "believes", "knows", "desires", "holdsDuring", "holds", "necessarily", "possibly" -> unknown(e, scope)
                else -> KifFormula.Predicate(head, args.map { term(it, scope) })
            }
        }

        private fun unknown(e: KifExpr, scope: Map<String, Int>): KifFormula.Unsupported {
            fun variables(node: KifExpr, bound: Set<String>) {
                when (node) {
                    is KifExpr.Var -> if (node.name !in bound) free.add(node.name)
                    is KifExpr.Quoted -> Unit
                    is KifExpr.ListExpr -> {
                        val parts = node.elements
                        val head = parts.firstOrNull()?.let(::atom)
                        if (head in setOf("forall", "exists") && parts.size == 3) {
                            val binders = (parts[1] as? KifExpr.ListExpr)?.elements.orEmpty()
                                .filterIsInstance<KifExpr.Var>().map { it.name }
                            variables(parts[2], bound + binders)
                        } else parts.forEach { variables(it, bound) }
                    }
                    else -> Unit
                }
            }
            variables(e, scope.keys)
            unsupported.add(e)
            return KifFormula.Unsupported(e)
        }
    }
}
