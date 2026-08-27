package borg.trikeshed.lcnc

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries

/**
 * FrameId — the scope frame's rolling cid ([Frame] algebra in
 * `borg.trikeshed.modelmux`): scope prefix + frame turn = the same structure
 * the address grammar routes three ways (cache affinity, scope nesting,
 * network distribution). A typealias with a rolling [append]/[root] pair;
 * identity is ContentId over parent-cid-bytes ++ scope name.
 */
typealias FrameId = FrameIdChain
const val ROOT_SCOPE = "root"

data class FrameIdChain(val cid: ContentId, val parent: ContentId? = null) {
    companion object {
        fun root(scope: String): FrameIdChain =
            FrameIdChain(ContentId.of(scope.encodeToByteArray()), parent = null)

        fun append(parent: FrameIdChain, scope: String): FrameIdChain =
            FrameIdChain(
                cid = ContentId.of(parent.cid.hex.encodeToByteArray() + scope.encodeToByteArray()),
                parent = parent.cid,
            )
    }
}

/**
 * ProgramNavigator — the fractal dive: a `program.ref` node is a window onto
 * another program living at the SAME zoomable surface, not a function call.
 * `diveInto` swaps [current] for the referenced program and remembers how to
 * get back; `popTo` restores it. Because a dived-into program can itself hold
 * a `program.ref`, this recurses to whatever depth the stored programs
 * actually nest — the stack is the only thing that makes that recursion safe
 * (a naive "load and forget" would strand the climb-back-out path).
 *
 * Direct port of `panels.html`'s `diveStack`/`diveInto`/`popTo` — commonMain
 * so the semantics are proven by a real test suite instead of a browser
 * screenshot. [loader] is the one platform seam: JS wires it to `fetch`
 * against `/api/panels/<name>`, tests wire it to an in-memory map.
 */
class ProgramNavigator(
    root: LcncProgram,
    private val loader: suspend (String) -> LcncProgram?,
) {
    /** One entry per dive: the name we dove INTO, and the program we left behind to get there. */
    private data class Frame(val name: String, val left: LcncProgram)

    private val frames = ArrayList<Frame>()

    var current: LcncProgram = root
        private set

    /** Names from root to [current], most recently dove-into last. Empty at root. */
    val breadcrumb: Series<String>
        get() = frames.map { it.name }.toSeries()

    val depth: Int get() = frames.size

    /**
     * Scope entry as a frame push (plan step 3): diving into [name] advances
     * the rolling frame chain — the parent frame's cid ++ the scope name —
     * so scope nesting IS the cid chain: the same prefix routes cache
     * affinity, scope nesting, and network distribution. The chain starts at
     * the navigator's root scope name and grows one [FrameId] per dive.
     */
    var frameChain: FrameId = FrameId.root(ROOT_SCOPE)
        private set

    sealed class DiveResult {
        data object Ok : DiveResult()
        data class NotFound(val name: String) : DiveResult()
    }

    /** Fetch [name] via [loader], push the frame, and descend into it. No-op stack push on failure. */
    suspend fun diveInto(name: String): DiveResult {
        val loaded = loader(name) ?: return DiveResult.NotFound(name)
        frames.add(Frame(name, current))
        frameChain = FrameId.append(frameChain, name)
        current = loaded
        return DiveResult.Ok
    }

    /**
     * Climb back to the state after exactly [toDepth] dives (0 = root, before
     * any dive; 1 = wherever the first dive landed; and so on) — the meaning
     * a breadcrumb's Nth segment has: "take me back to here." [toDepth] >=
     * the current [depth] is rejected, not clamped — there is nothing to pop
     * to, and silently landing somewhere else would be worse than a no-op.
     * The frame chain rewinds with the frames: scope exit pops the frame.
     */
    fun popTo(toDepth: Int): Boolean {
        if (toDepth < 0 || toDepth >= frames.size) return false
        current = frames[toDepth].left
        // Rewind the chain by replaying the surviving scope path from the root:
        // frame cids are a pure function of the scope names, so the rebuilt
        // chain is bit-identical to a fresh dive through the same path.
        val surviving = frames.subList(0, toDepth).map { it.name }
        var chain = FrameId.root(ROOT_SCOPE)
        for (name in surviving) chain = FrameId.append(chain, name)
        while (frames.size > toDepth) frames.removeAt(frames.size - 1)
        frameChain = chain
        return true
    }

    /** Convenience: one level up. False at root (nothing to pop). */
    fun pop(): Boolean = if (frames.isEmpty()) false else popTo(frames.size - 1)
}
