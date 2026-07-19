package borg.trikeshed.forge.ccek

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.ElementState
import borg.trikeshed.forge.ForgeDocument
import borg.trikeshed.kanban.KanbanBoard
import kotlinx.coroutines.CoroutineContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Forge workspace element — replaces [CCEK.ArticulatedNode] and [CCEK.CcekReactorBinding].
 *
 * Lifecycle is the standard CCEK element lifecycle:
 *   CREATED → open() → ACTIVE → drain() → CLOSED
 *
 * The element holds the live [ForgeDocument] and fans out [ForgeProjection] values
 * to downstream subscribers via the standard [fanoutSubscribers] channel mechanism.
 * No manual [Job] / [CoroutineScope] / [Channel] juggling — the [supervisor] and
 * [fanoutSubscribers] from [AsyncContextElement] subsume all of that.
 */
class ForgeElement(
    initialDoc: ForgeDocument,
    parentJob: kotlinx.coroutines.Job? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob) {

    companion object Key : CoroutineContext.Key<ForgeElement>

    override val key: CoroutineContext.Key<*> get() = Key

    /** Current document state — mutated only by [applySignal] in ACTIVE state. */
    private var doc: ForgeDocument = initialDoc

    /** Input channel for external signals (keystrokes, board moves, ingest). */
    val signalIn: Channel<ForgeSignal> = Channel(Channel.UNLIMITED)

    /** Projection flows — downstream consumers observe these via [fanoutSubscribers]. */
    private val _documentProjections = MutableSharedFlow<ForgeDocument>(replay = 64, extraBufferCapacity = 64)
    val documentProjections: SharedFlow<ForgeDocument> = _documentProjections.asSharedFlow()

    private val _boardProjections = MutableSharedFlow<KanbanBoard>(replay = 64, extraBufferCapacity = 64)
    val boardProjections: SharedFlow<KanbanBoard> = _boardProjections.asSharedFlow()

    private val _markdownProjections = MutableSharedFlow<String>(replay = 64, extraBufferCapacity = 64)
    val markdownProjections: SharedFlow<String> = _markdownProjections.asSharedFlow()

    /** Start the signal-processing loop. Called by [open()]. */
    private var processorJob: kotlinx.coroutines.Job? = null

    override suspend fun open() {
        super.open() // CREATED -> OPEN
        if (state != ElementState.OPEN) return

        // Transition to ACTIVE and start the processor
        state = ElementState.ACTIVE
        emitProjections()

        processorJob = supervisor.launch {
            for (signal in signalIn) {
                if (state != ElementState.ACTIVE) break
                applySignal(signal)
                emitProjections()
            }
        }
    }

    override suspend fun drain() {
        if (state != ElementState.ACTIVE) return
        state = ElementState.DRAINING
        // Drain remaining signals in the channel
        while (true) {
            val signal = signalIn.poll()
            if (signal == null) break
            applySignal(signal)
            emitProjections()
        }
        close()
    }

    override suspend fun close() {
        processorJob?.cancel()
        processorJob = null
        signalIn.close()
        super.close() // -> CLOSED
    }

    /** Apply a single signal to the document. Override or extend for new signal types. */
    private fun applySignal(signal: ForgeSignal) {
        doc = when (signal) {
            is ForgeSignal.AppendBlock -> doc.appendBlock(signal.kind, signal.text, signal.properties)
            is ForgeSignal.UpdateText -> doc.updateText(signal.blockId, signal.text)
            is ForgeSignal.DeleteBlock -> doc.deleteBlock(signal.blockId)
            is ForgeSignal.MoveCard -> doc.moveCard(signal.cardId, signal.toColumnId)
        }
    }

    /** Emit current projections to all downstream subscribers. */
    private fun emitProjections() {
        _documentProjections.tryEmit(doc)
        _boardProjections.tryEmit(doc.toKanbanBoard())
        _markdownProjections.tryEmit(doc.toMarkdown())
    }

    /** Current document snapshot (for synchronous reads). */
    val currentDoc: ForgeDocument get() = doc
}

/** Factory function for the standard reactor binding pattern. */
fun ForgeElement.initialize(
    reactor: borg.trikeshed.userspace.reactor.MuxReactorElement,
    seed: ForgeDocument = ForgeDocument.empty(),
): ForgeElement {
    return ForgeElement(seed, reactor.supervisor)
}