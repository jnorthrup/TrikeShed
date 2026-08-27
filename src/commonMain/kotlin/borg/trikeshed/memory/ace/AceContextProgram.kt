package borg.trikeshed.memory.ace

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.modelmux.Frame
import borg.trikeshed.memory.MemoryStore
import borg.trikeshed.memory.memoryFile

/** Step K: the context policy IS this auditable LCNC program document. */
object AceContextProgram {
    enum class FrameKind {
        TOOLS_SYSTEM,
        PLAYBOOK_BASE,
        ENVELOPE,
        VOLATILE_TAIL,
        PREWARM,
        FANOUT_STAGGER,
    }

    data class Config(
        val model: String,
        val tools: Series<String>,
        val effort: String,
    ) {
        /** Config salt: model + deterministically sorted tool set + effort. */
        fun salt(): ContentId {
            val sorted = Array(tools.size) { i -> tools[i] }
            sorted.sort()
            val bytes = buildString {
                append("ace-config-v1\nmodel=").append(model).append("\neffort=").append(effort).append("\ntools=").append(sorted.size).append('\n')
                for (tool in sorted) append(tool).append('\n')
            }.encodeToByteArray()
            return ContentId.of(bytes)
        }
    }

    data class Node(
        val id: String,
        val kind: FrameKind,
        val payload: ByteArray,
        val cacheBreakpoint: Boolean = false,
        val maxTokens: Int? = null,
        /** FANOUT_STAGGER only: dispatch remaining specialists on first streamed token. */
        val dispatchOnFirstToken: Boolean = false,
    ) {
        override fun equals(other: Any?): Boolean = other is Node && id == other.id && kind == other.kind &&
            payload.contentEquals(other.payload) && cacheBreakpoint == other.cacheBreakpoint &&
            maxTokens == other.maxTokens && dispatchOnFirstToken == other.dispatchOnFirstToken
        override fun hashCode(): Int = id.hashCode() * 31 + payload.contentHashCode()
    }

    data class Program(val name: String, val nodes: Series<Node>) {
        init {
            var breakpoints = 0
            for (i in 0 until nodes.size) if (nodes[i].cacheBreakpoint) breakpoints++
            require(breakpoints <= 4) { "Anthropic lane supports at most 4 explicit cache breakpoints" }
            for (i in 1 until nodes.size) {
                require(stabilityRank(nodes[i - 1].kind) <= stabilityRank(nodes[i].kind)) {
                    "context nodes must be in change-frequency order"
                }
            }
            for (i in 0 until nodes.size) {
                val n = nodes[i]
                if (n.kind == FrameKind.PREWARM) require(n.maxTokens == 0) { "prewarm node requires maxTokens=0" }
                if (n.kind == FrameKind.FANOUT_STAGGER) require(n.dispatchOnFirstToken) { "fan-out stagger dispatches on first token" }
            }
        }

        /** Deterministic program document bytes; wires are adjacent node ids in chain order. */
        fun canonicalBytes(): ByteArray = buildString {
            append("ace-context-program-v1\nname=").append(name).append("\nnodes=").append(nodes.size).append('\n')
            for (i in 0 until nodes.size) {
                val n = nodes[i]
                append("node=").append(n.id).append('|').append(n.kind.name).append('|')
                    .append(if (n.cacheBreakpoint) 1 else 0).append('|').append(n.maxTokens ?: -1).append('|')
                    .append(if (n.dispatchOnFirstToken) 1 else 0).append('|')
                    .append(ContentId.of(n.payload).value).append('\n')
                if (i > 0) append("wire=").append(nodes[i - 1].id).append("->").append(n.id).append('\n')
            }
        }.encodeToByteArray()
    }

    /** Required ACE preset, encoded as nodes rather than tribal knowledge. */
    fun preset(
        toolsSystem: ByteArray,
        playbookBase: ByteArray,
        envelope: ByteArray,
        volatileTail: ByteArray,
    ): Program {
        val nodes = arrayOf(
            Node("tools-system", FrameKind.TOOLS_SYSTEM, toolsSystem, cacheBreakpoint = true),
            Node("playbook-base", FrameKind.PLAYBOOK_BASE, playbookBase, cacheBreakpoint = true),
            Node("envelope", FrameKind.ENVELOPE, envelope, cacheBreakpoint = true),
            Node("prewarm", FrameKind.PREWARM, ByteArray(0), maxTokens = 0),
            Node("fanout-stagger", FrameKind.FANOUT_STAGGER, ByteArray(0), dispatchOnFirstToken = true),
            Node("volatile-tail", FrameKind.VOLATILE_TAIL, volatileTail, cacheBreakpoint = true),
        )
        return Program("adaptive-context", nodes.size j { i: Int -> nodes[i] })
    }

    /** Persist the LCNC policy document under a content-addressed program path. */
    fun land(program: Program, store: MemoryStore): ContentId {
        val bytes = program.canonicalBytes()
        val cid = ContentId.of(bytes)
        val path = "/programs/context/${cid.hex}.ace"
        return store.put(memoryFile(path, "Adaptive context LCNC program ${program.name}", bytes), "ace", "lcnc-program")
    }

    /**
     * Build the rolling cache-identity chain. Every turn's canonical bytes include config salt,
     * kind, policy flags, and the exact prompt payload. A config change therefore forks the chain.
     */
    fun assemble(program: Program, config: Config): Series<Frame> {
        if (program.nodes.size == 0) return 0 j { _: Int -> error("empty") }
        val frames = arrayOfNulls<Frame>(program.nodes.size)
        val salt = config.salt().value
        for (i in 0 until program.nodes.size) {
            val n = program.nodes[i]
            val bytes = buildString {
                append("salt=").append(salt).append('\n')
                append("id=").append(n.id).append("\nkind=").append(n.kind.name).append('\n')
                append("breakpoint=").append(n.cacheBreakpoint).append("\nmaxTokens=").append(n.maxTokens ?: -1).append('\n')
                append("dispatchOnFirstToken=").append(n.dispatchOnFirstToken).append("\n")
            }.encodeToByteArray() + n.payload
            frames[i] = if (i == 0) Frame.root(bytes) else Frame.append(frames[i - 1]!!, bytes)
        }
        return frames.size j { i: Int -> frames[i]!! }
    }

    data class ChunkReceipt(val frameCid: ContentId, val cacheRead: Long, val cacheWrite: Long)

    /** First changed chain frame; -1 means byte-identical chain. Everything after this is repriced. */
    fun firstChanged(a: Series<Frame>, b: Series<Frame>): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) if (a[i].cid != b[i].cid) return i
        return if (a.size == b.size) -1 else n
    }

    /** Identical prefix receipts must report cache_read coverage through the shared prefix. */
    fun auditCacheRead(chain: Series<Frame>, receipts: Series<ChunkReceipt>, throughExclusive: Int): Boolean {
        if (throughExclusive > chain.size) return false
        for (i in 0 until throughExclusive) {
            var found = false
            for (r in 0 until receipts.size) {
                if (receipts[r].frameCid == chain[i].cid && receipts[r].cacheRead > 0) { found = true; break }
            }
            if (!found) return false
        }
        return true
    }

    private fun stabilityRank(kind: FrameKind): Int = when (kind) {
        FrameKind.TOOLS_SYSTEM -> 0
        FrameKind.PLAYBOOK_BASE -> 1
        FrameKind.ENVELOPE -> 2
        FrameKind.PREWARM -> 3
        FrameKind.FANOUT_STAGGER -> 4
        FrameKind.VOLATILE_TAIL -> 5
    }
}
