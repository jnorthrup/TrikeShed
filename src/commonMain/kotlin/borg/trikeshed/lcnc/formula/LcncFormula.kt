package borg.trikeshed.lcnc.formula

import borg.trikeshed.lcnc.collections.associative.PropertyType
import borg.trikeshed.lcnc.collections.associative.PropertyValue

/**
 * Basic AST for LCNC Formulas.
 */
sealed class FormulaAST {
    abstract fun evaluate(row: Map<String, PropertyValue>): Any?
}

data class NumberLiteral(val value: Double) : FormulaAST() {
    override fun evaluate(row: Map<String, PropertyValue>): Any? = value
}

data class StringLiteral(val value: String) : FormulaAST() {
    override fun evaluate(row: Map<String, PropertyValue>): Any? = value
}

data class BooleanLiteral(val value: Boolean) : FormulaAST() {
    override fun evaluate(row: Map<String, PropertyValue>): Any? = value
}

data class PropFunction(val propertyName: String) : FormulaAST() {
    override fun evaluate(row: Map<String, PropertyValue>): Any? {
        val prop = row[propertyName] ?: return null
        return prop.value
    }
}

data class IfFunction(val condition: FormulaAST, val thenExpr: FormulaAST, val elseExpr: FormulaAST) : FormulaAST() {
    override fun evaluate(row: Map<String, PropertyValue>): Any? {
        val condValue = condition.evaluate(row)
        val isTrue = condValue == true || (condValue is Number && condValue.toDouble() != 0.0)
        return if (isTrue) thenExpr.evaluate(row) else elseExpr.evaluate(row)
    }
}

/**
 * A simple regex-based parser for LCNC formulas.
 * Supports: `prop("Name")`, `if(cond, then, else)`, literals.
 */
class FormulaParser(val source: String) {
    private var pos = 0
    
    private fun peek(): Char? = if (pos < source.length) source[pos] else null
    private fun advance() { if (pos < source.length) pos++ }
    private fun skipWhitespace() {
        while (peek()?.isWhitespace() == true) advance()
    }

    fun parse(): FormulaAST {
        skipWhitespace()
        val ast = parseExpression()
        skipWhitespace()
        require(pos == source.length) { "Unexpected characters at end of formula: ${source.substring(pos)}" }
        return ast
    }

    private fun parseExpression(): FormulaAST {
        skipWhitespace()
        val c = peek() ?: throw IllegalArgumentException("Unexpected end of formula")
        
        return when {
            c.isDigit() -> parseNumber()
            c == '"' -> parseString()
            c.isLetter() -> parseIdentifierOrFunction()
            else -> throw IllegalArgumentException("Unexpected character '$c' at position $pos")
        }
    }

    private fun parseNumber(): FormulaAST {
        val start = pos
        while (peek()?.isDigit() == true || peek() == '.') advance()
        return NumberLiteral(source.substring(start, pos).toDouble())
    }

    private fun parseString(): FormulaAST {
        advance() // skip quote
        val start = pos
        while (peek() != null && peek() != '"') advance()
        val str = source.substring(start, pos)
        if (peek() == '"') advance()
        return StringLiteral(str)
    }

    private fun parseIdentifierOrFunction(): FormulaAST {
        val start = pos
        while (peek()?.isLetterOrDigit() == true) advance()
        val ident = source.substring(start, pos)

        skipWhitespace()
        if (peek() == '(') {
            advance() // skip '('
            val args = mutableListOf<FormulaAST>()
            skipWhitespace()
            if (peek() != ')') {
                while (true) {
                    args.add(parseExpression())
                    skipWhitespace()
                    if (peek() == ',') {
                        advance()
                    } else {
                        break
                    }
                }
            }
            require(peek() == ')') { "Expected ')' at position $pos" }
            advance() // skip ')'
            
            return when (ident) {
                "if" -> {
                    require(args.size == 3) { "'if' function requires exactly 3 arguments" }
                    IfFunction(args[0], args[1], args[2])
                }
                "prop" -> {
                    require(args.size == 1) { "'prop' function requires exactly 1 argument" }
                    val arg = args[0] as? StringLiteral ?: throw IllegalArgumentException("'prop' argument must be a string literal")
                    PropFunction(arg.value)
                }
                "true" -> BooleanLiteral(true)
                "false" -> BooleanLiteral(false)
                else -> throw IllegalArgumentException("Unknown function '$ident'")
            }
        }
        
        return when (ident) {
            "true" -> BooleanLiteral(true)
            "false" -> BooleanLiteral(false)
            else -> throw IllegalArgumentException("Unknown identifier '$ident'")
        }
    }
}

/**
 * Applies a formula to a row, computing a new column value.
 */
class FormulaReducer(source: String) {
    private val ast = FormulaParser(source).parse()

    fun reduce(row: Map<String, PropertyValue>): Any? {
        return ast.evaluate(row)
    }
}
