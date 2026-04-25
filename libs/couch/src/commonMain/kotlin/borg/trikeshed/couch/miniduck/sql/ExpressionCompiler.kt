package borg.trikeshed.couch.miniduck.sql

import borg.trikeshed.parse.kursive.sql.*
import borg.trikeshed.couch.miniduck.exec.RowAccessor

// Simple expression/predicate compiler used by the planner. Keep it small and explicit
fun compileExpression(expr: Expr, ctx: PlannerContext): (RowAccessor) -> Any? {
    // Column reference
    if (expr is ColumnRef) {
        return { row: RowAccessor -> row.get(expr.id) }
    }

    // Literal
    if (expr is LitExpr) {
        val litVal: Any? = when (val lit = expr.lit) {
            is StringLiteral -> lit.asString()
            is NumericLiteral -> lit.value
            else -> null
        }
        return { _ -> litVal }
    }

    // Binary expression
    if (expr is BinaryExpr) {
        val leftFn = compileExpression(expr.left, ctx)
        val rightFn = compileExpression(expr.right, ctx)
        val opRaw = expr.op
        val op = opRaw.uppercase()
        return { row: RowAccessor ->
            val l = leftFn(row)
            val r = rightFn(row)
            when (op) {
                "+" -> if (l is Number && r is Number) l.toDouble() + r.toDouble() else null
                "-" -> if (l is Number && r is Number) l.toDouble() - r.toDouble() else null
                "*" -> if (l is Number && r is Number) l.toDouble() * r.toDouble() else null
                "/" -> if (l is Number && r is Number && r.toDouble() != 0.0) l.toDouble() / r.toDouble() else null
                "=" -> if (l is Number && r is Number) compareNumbers(l, r) == 0 else l == r
                "!=", "<>" -> if (l is Number && r is Number) compareNumbers(l, r) != 0 else l != r
                ">" -> compareNumbers(l, r) > 0
                "<" -> compareNumbers(l, r) < 0
                ">=" -> compareNumbers(l, r) >= 0
                "<=" -> compareNumbers(l, r) <= 0
                "LIKE" -> l?.toString()?.contains(r?.toString() ?: "") ?: false
                else -> null
            }
        }
    }

    // Fallback
    return { _ -> null }
}

fun compilePredicate(expr: Expr, ctx: PlannerContext): (RowAccessor) -> Boolean {
    if (expr is BinaryExpr) {
        val opRaw = expr.op
        val op = opRaw.uppercase()
        if (op == "AND") {
            val l = compilePredicate(expr.left, ctx)
            val r = compilePredicate(expr.right, ctx)
            return { row: RowAccessor -> l(row) && r(row) }
        }
        if (op == "OR") {
            val l = compilePredicate(expr.left, ctx)
            val r = compilePredicate(expr.right, ctx)
            return { row: RowAccessor -> l(row) || r(row) }
        }
        // other binary exprs - treat as truthy expression
        val e = compileExpression(expr, ctx)
        return { row: RowAccessor ->
            val v = e(row)
            when (v) {
                is Boolean -> v
                null -> false
                else -> (v as? Number)?.toDouble()?.let { it != 0.0 } ?: true
            }
        }
    }

    // non-binary expressions - treat as truthy
    val e = compileExpression(expr, ctx)
    return { row: RowAccessor ->
        val v = e(row)
        when (v) {
            is Boolean -> v
            null -> false
            else -> (v as? Number)?.toDouble()?.let { it != 0.0 } ?: true
        }
    }
}

fun compareNumbers(a: Any?, b: Any?): Int {
    val an = a as? Number
    val bn = b as? Number
    if (an != null && bn != null) {
        val aIntegral = an is Long || an is Int || an is Short || an is Byte
        val bIntegral = bn is Long || bn is Int || bn is Short || bn is Byte
        return if (aIntegral && bIntegral) an.toLong().compareTo(bn.toLong()) else an.toDouble().compareTo(bn.toDouble())
    }
    val asStr = a?.toString()
    val bsStr = b?.toString()
    if (asStr != null && bsStr != null) return asStr.compareTo(bsStr)
    if (asStr == null && bsStr == null) return 0
    return if (asStr == null) -1 else 1
}
