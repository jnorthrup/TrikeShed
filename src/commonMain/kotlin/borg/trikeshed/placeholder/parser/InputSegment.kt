package borg.trikeshed.placeholder.parser

import kotlinx.cinterop.COpaquePointer

/** InputSegment all input to the parser is done with MMAP'd buffers managed by InputSegment abstraction.
 * this is responsible for holding blackboard attributes, traits, and other state and contributes to the context of the parser.
 *
 */
data class InputSegment(
    val buffer: COpaquePointer,
    val length: Int,
    val state: LexerState)

