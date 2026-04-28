package borg.trikeshed.polyglot

import borg.trikeshed.lib.Series

/**
 * Native parser backends via tree-sitter C API FFI.
 *
 * Tree-sitter provides incremental, error-tolerant parsers for ~40 languages.
 * The FFI path:
 *   1. dlopen/dlsym the tree-sitter-<lang> shared library
 *   2. TSParser → TSTree → walk CST → emit SourceFragment tree
 *   3. Each CST node maps to a SourceFragment with exact byte spans
 *
 * The tree-sitter CST is inherently a block tree — parent nodes own
 * byte ranges that contain child nodes. This maps cleanly to ParseScope
 * regions: each CST node becomes a child ParseScope under SupervisorJob.
 */

/** Stub: generic tree-sitter parser backend. */
class TreeSitterParser(
    override val lang: LangId,
    private val libPath: String,  // path to tree-sitter-<lang>.so/.dylib
) : LangParser {

    override fun parse(source: Series<Char>): UniversalAst =
        TODO("TreeSitterParser($lang) — FFI to tree-sitter C API")

    override fun parseFile(path: String): UniversalAst =
        TODO("TreeSitterParser.parseFile($lang): $path")
}
