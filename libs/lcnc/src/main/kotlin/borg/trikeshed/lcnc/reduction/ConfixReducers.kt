package borg.trikeshed.lcnc.reduction

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*

/**
 * Confix-specific implementations for scan0 and buildTree as LcncReduction.
 * These are extracted from the Confix parser implementation.
 */
object ConfixReducers {

    /** Scan0: single-pass token recognition producing spans/tags. */
    data class ScanToken(
        val tag: String,
        val span: SpanEvent.Span,
        val depth: Int,
        val parentSpan: SpanEvent.Span?
    )

    /**
     * Scan0 folder: processes bytes → produces ScanTokens.
     * This is the MAP phase for Confix.
     */
    class Scan0Folder : Folder<Byte, List<ScanToken>> {
        private var buffer = StringBuilder()
        private var depth = 0
        private var pos = 0
        private var inString = false
        private var escapeNext = false

        override fun fold(acc: List<ScanToken>, input: Byte): List<ScanToken> {
            val char = input.toInt().toChar()
            // Simplified JSON-like scanning — actual impl handles full Confix syntax
            when {
                char == '{' || char == '[' -> {
                    if (!inString) {
                        depth++
                        buffer.append(char)
                    } else {
                        buffer.append(char)
                    }
                }
                char == '}' || char == ']' -> {
                    if (!inString) {
                        val span = SpanEvent.Span(pos - buffer.length, pos)
                        val token = ScanToken(buffer.toString(), span, depth, null)
                        depth = maxOf(0, depth - 1)
                        buffer = StringBuilder()
                        return acc + token
                    } else {
                        buffer.append(char)
                    }
                }
                char == '"' -> {
                    if (!escapeNext) inString = !inString
                    buffer.append(char)
                    escapeNext = char == '\\' && !escapeNext
                }
                else -> buffer.append(char)
            }
            pos++
            return acc
        }
    }

    /** BuildTree: folds ScanTokens into a TreeBuilderState (Cursor). */
    class BuildTreeFolder : Folder<ScanToken, TreeBuilderState> {
        override fun fold(acc: TreeBuilderState, input: ScanToken): TreeBuilderState {
            val node = TreeBuilderState.TreeNode(input.tag)
            val newStack = acc.stack.toMutableList()
            val newRoots = acc.roots.toMutableList()

            // Single-pass parent tracking using stack (O(n))
            while (newStack.isNotEmpty() && newStack.last().span != null &&
                   newStack.last().span!!.endInclusive <= (input.span?.start ?: 0)) {
                val closed = newStack.removeAt(newStack.lastIndex)
                if (newStack.isEmpty()) {
                    newRoots.add(closed)
                } else {
                    newStack.last().children.add(closed)
                }
            }

            if (newStack.isNotEmpty()) {
                newStack.last().children.add(node)
            } else {
                newRoots.add(node)
            }
            newStack.add(node)
            return TreeBuilderState(newStack, newRoots)
        }
    }

    /** TreeBuilderMerger: merges partial tree states (for incremental parse). */
    class TreeBuilderMerger : Merger<TreeBuilderState> {
        override fun merge(partials: Series<TreeBuilderState>): TreeBuilderState {
            if (partials.size == 0) return TreeBuilderState()
            var result = partials[0]
            for (i in 1 until partials.size) {
                result = mergeTwo(result, partials[i])
            }
            return result
        }

        private fun mergeTwo(a: TreeBuilderState, b: TreeBuilderState): TreeBuilderState {
            // Merge roots; stacks should be empty for complete trees
            return TreeBuilderState(
                stack = a.stack + b.stack,
                roots = a.roots + b.roots
            )
        }
    }

    /** Incremental parse: patch existing index with edits. */
    data class IncrementalPatch(
        val oldIndex: TreeBuilderState,
        val edits: List<TextEdit>
    )

    data class TextEdit(val start: Int, val end: Int, val replacement: String)

    /**
     * Patch an existing parse tree with edits.
     * Returns new TreeBuilderState without full reparse.
     */
    fun patch(oldIndex: TreeBuilderState, edits: List<TextEdit>): TreeBuilderState {
        // Placeholder — actual implementation re-scans affected regions
        // and re-folds only the changed spans
        return oldIndex
    }

    /** Streaming parse: yields Cursor chunks. */
    class StreamingParser {
        private var state = TreeBuilderState()
        private val folder = BuildTreeFolder()
        private val scanFolder = Scan0Folder()

        fun parseChunk(bytes: ByteArray): Cursor {
            val tokens = bytes.fold(emptyList<ScanToken>()) { acc, b -> scanFolder.fold(acc, b) }
            state = tokens.fold(state) { acc, t -> folder.fold(acc, t) }
            // Convert state.roots to Cursor chunk
            return rootsToCursor(state.roots)
        }

        private fun rootsToCursor(roots: List<TreeBuilderState.TreeNode>): Cursor {
            // Convert tree nodes to RowVec cursor
            return emptySeriesOf()
        }
    }

    /** Expose scanIndex as LcncReduction phase MAP output for Forge CursorSource integration. */
    fun scanIndex(): LcncReduction<ConfixStructuralKey, Byte, List<ScanToken>, List<ScanToken>> {
        val keyAlg = object : KeyAlg<ConfixStructuralKey> {
            override val extractor: KeyExtractor<Any, ConfixStructuralKey> =
                KeyExtractor { ConfixStructuralKey(0, 0, 0) }
            override val hierarchy: KeyHierarchy<ConfixStructuralKey> = LcncKeyAlg.confixStructuralKey()
            override val order: KeyOrder<ConfixStructuralKey> = object : KeyOrder<ConfixStructuralKey> {
                override fun compare(a: ConfixStructuralKey, b: ConfixStructuralKey): Int = a.depth.compareTo(b.depth)
            }
        }

        val valueAlg = object : ValueAlg<Byte, List<ScanToken>> {
            override val folder: Folder<Byte, List<ScanToken>> = Scan0Folder()
            override val merger: Merger<List<ScanToken>> = Merger { partials ->
                partials.toList().flatten()
            }
            override val initial: List<ScanToken> = emptyList()
        }

        val phaseAlg = LcncPhaseAlg.confixPhaseAlg
        val carrierAlg = LcncCarrierAlg.seriesCarrierAlg<Byte>()

        return object : AbstractLcncReduction<ConfixStructuralKey, Byte, List<ScanToken>, List<ScanToken>>(
            keyAlg, valueAlg, phaseAlg, carrierAlg
        ) {
            override protected fun formatOutput(reduced: Any): List<ScanToken> {
                val carrier = reduced as ReductionCarrier<Join<ConfixStructuralKey, List<ScanToken>>>
                return carrier.fold(emptyList<ScanToken>()) { acc, join -> acc + join.b }
            }
        }
    }
}