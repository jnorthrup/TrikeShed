package borg.trikeshed.userspace.containment

import kotlin.math.log2

data class LintResult(val clean: Boolean, val reason: String? = null)

/**
 * Legion Doc 04 Layer 4 — AST-enforced semantic linter.
 * Parses Kotlin patch diffs into a rudimentary AST sequence to detect
 * obfuscated payloads, high-entropy variable names, and whitespace
 * steganography before ingestion.
 */
object PatchAstLinter {

    sealed class AstNode {
        data class Declaration(val type: String, val name: String) : AstNode()
        data class StringLiteral(val content: String) : AstNode()
        data class Comment(val content: String) : AstNode()
        data class RawLine(val content: String) : AstNode()
    }

    fun lint(patch: String): LintResult {
        val addedLines = patch.lines()
            .filter { it.startsWith("+") && !it.startsWith("+++") }
            .map { it.substring(1) }

        val addedText = addedLines.joinToString("\n")
        val ast = parseRudimentaryAst(addedText)

        // 1. Whitespace Steganography Modulations (from raw lines)
        for (node in ast) {
            if (node is AstNode.RawLine) {
                val inlineWhitespaceRegex = Regex("[ \\t]{8,}")
                for (match in inlineWhitespaceRegex.findAll(node.content)) {
                    if (' ' in match.value && '\t' in match.value) {
                        return LintResult(false, "Whitespace modulation detected: inline run of 8+ mixed spaces and tabs")
                    }
                }

                val trailingWhitespaceRegex = Regex("[ \\t]+$")
                val trailingMatch = trailingWhitespaceRegex.find(node.content)
                if (trailingMatch != null && ' ' in trailingMatch.value && '\t' in trailingMatch.value) {
                    return LintResult(false, "Whitespace modulation detected: mixed trailing whitespace")
                }
            }
        }

        // 2. High Entropy Variable Names
        for (node in ast) {
            if (node is AstNode.Declaration) {
                if (calculateShannonEntropy(node.name) > 4.0) {
                    return LintResult(false, "High-entropy identifier detected: ${node.name}")
                }
            }
        }

        // 3. Suspicious Payloads (Comments and Strings)
        for (node in ast) {
            if (node is AstNode.Comment) {
                if (node.content.length > 200) {
                    return LintResult(false, "Suspicious comment payload (>200 chars)")
                }
            }
            if (node is AstNode.StringLiteral) {
                if (node.content.length > 200) {
                    return LintResult(false, "Suspicious string payload (>200 chars)")
                }
            }
        }

        return LintResult(true)
    }

    /**
     * Parses the patch text into a basic Abstract Syntax Tree representation
     * capturing Declarations, Comments, Strings, and Raw lines.
     */
    private fun parseRudimentaryAst(text: String): List<AstNode> {
        val nodes = mutableListOf<AstNode>()

        // Add raw lines for whitespace steganography checks
        for (line in text.lines()) {
            nodes.add(AstNode.RawLine(line))
        }

        // Extract Declarations
        val declRegex = Regex("\\b(val|var|fun|class|interface|object)\\s+([a-zA-Z0-9_]+)\\b")
        for (match in declRegex.findAll(text)) {
            nodes.add(AstNode.Declaration(match.groupValues[1], match.groupValues[2]))
        }

        // Extract Strings
        val stringRegex = Regex("\"(?:[^\"\\\\]|\\\\.)*\"")
        for (match in stringRegex.findAll(text)) {
            nodes.add(AstNode.StringLiteral(match.value))
        }

        // Extract Triple Strings
        val tripleStringRegex = Regex("\"\"\"[\\s\\S]*?\"\"\"")
        for (match in tripleStringRegex.findAll(text)) {
            nodes.add(AstNode.StringLiteral(match.value))
        }

        // Extract Single Line Comments
        val singleLineCommentRegex = Regex("//.*")
        for (match in singleLineCommentRegex.findAll(text)) {
            nodes.add(AstNode.Comment(match.value))
        }

        // Extract Block Comments
        val blockCommentRegex = Regex("/\\*[\\s\\S]*?\\*/")
        for (match in blockCommentRegex.findAll(text)) {
            nodes.add(AstNode.Comment(match.value))
        }

        return nodes
    }

    private fun calculateShannonEntropy(s: String): Double {
        if (s.isEmpty()) return 0.0
        val counts = mutableMapOf<Char, Int>()
        for (c in s) counts[c] = (counts[c] ?: 0) + 1
        var entropy = 0.0
        val len = s.length.toDouble()
        for (count in counts.values) {
            val p = count / len
            entropy -= p * log2(p)
        }
        return entropy
    }
}
