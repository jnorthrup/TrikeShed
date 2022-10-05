package borg.trikeshed.placeholder.parser

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer

data class LexerState(
    val mode: LexerMode,
    val input: CPointer<ByteVar>,
    val inputEnd: CPointer<ByteVar>,
    val position: CPointer<ByteVar>,
    val line: Int,
    val column: Int,
    val index: Int,
    val offset: Int,
    val path: String,
    val filename: String,
    val extension: String,
    val basename: String,
    val parent: String,
    val traits: List<Trait>,
    val parser: Parser,
    val fsm: FSM,
    val lexer: Lexer,
    val blackboard: BlackBoard
)
