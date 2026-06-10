/*
 * JS implementation of Jep466PlatformParser
 * Provides a minimal ClassFile parser for Kotlin/JS targets.
 */
package org.xvm.activejs

import org.xvm.cursor.PointcutFacet
import borg.trikeshed.parse.confix.SaxEvent
import kotlin.js.JsModule
import kotlin.js.qualified

// @file:jsModule("jep466Parser")

@JsModule("jep466-parser")
@qualified
external fun parseClassFile(bytes: ByteArray): ParsedClassFile

@kotlin.js.JsModule("jep466-parser")
@qualified
external class ParsedClassFile(
    val className: String,
    val fields: Array<ParsedField>,
    val methods: Array<ParsedMethod>,
)

@kotlin.js.JsModule("jep466-parser")
@qualified
external class ParsedField(
    val name: String,
    val descriptor: String,
    val flags: Int,
)

@kotlin.js.JsModule("jep466-parser")
@qualified
external class ParsedMethod(
    val name: String,
    val descriptor: String,
    val flags: Int,
    val code: ParsedCode?,
)

@kotlin.js.JsModule("jep466-parser")
@qualified
external class ParsedCode(
    val bytecode: ByteArray,
    val maxStack: Int,
    val maxLocals: Int,
)

/**
 * JS/WASM ClassFile parser using a WASM-compiled Java classfile library.
 * This expect class is implemented in jep466Parser.js.kt for JS targets
 * and jep466Parser.wasm.kt for WASM targets.
 */
expect object Jep466PlatformParser {
    fun walkClassFile(bytes: ByteArray, action: (SaxEvent) -> Unit)
}