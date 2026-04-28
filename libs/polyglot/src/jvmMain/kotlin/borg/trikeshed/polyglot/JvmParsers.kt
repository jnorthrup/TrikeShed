package borg.trikeshed.polyglot

import borg.trikeshed.lib.Series

/**
 * JVM-specific language parser backends.
 *
 * - Java: javap -p -s output parsed into SourceFragments
 * - Kotlin: kotlinc IR dump (possible future path)
 * - JVM bytecode: ASM/ClassReader → SourceFragment (universal fallback for JVM langs)
 *
 * The JVM target can also load tree-sitter via JNI/JNA for C-based parsers.
 */

/** Stub: parse Java source via javap decompilation. */
object JavaParser : LangParser {
    override val lang: LangId = LangId.JAVA

    override fun parse(source: Series<Char>): UniversalAst =
        TODO("JavaParser via javap")

    override fun parseFile(path: String): UniversalAst =
        TODO("JavaParser.parseFile: $path")
}

/** Stub: parse Kotlin source via kotlinc IR dump. */
object KotlinParser : LangParser {
    override val lang: LangId = LangId.KOTLIN

    override fun parse(source: Series<Char>): UniversalAst =
        TODO("KotlinParser via kotlinc IR")

    override fun parseFile(path: String): UniversalAst =
        TODO("KotlinParser.parseFile: $path")
}
