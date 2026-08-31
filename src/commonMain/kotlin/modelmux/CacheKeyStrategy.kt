package modelmux

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import modelmux.acp.AcpMessage

/**
 * How a request becomes a CACHE IDENTITY.
 *
 * The mux had exactly one answer: sha256 of the canonical request bytes
 * ([ExactContentId], the M3 invariant). That is the right DEFAULT and it stays
 * the default — it is the only identity that can never produce a wrong reply,
 * and it is what the repair contract pins. But byte-exactness is also brutally
 * literal: re-indent a system prompt, re-wrap a paragraph, let an editor add a
 * trailing newline, and a semantically identical request misses a warm cache and
 * is paid for again at full price.
 *
 * So identity becomes a strategy, and several can be consulted in order
 * ([CacheCascade]). Each strategy is a *deliberate* statement about which
 * differences between two requests are allowed to be considered the same
 * request. That is a correctness trade, never a free optimisation, which is why
 * every relaxed strategy is opt-in and why the boundaries below exist.
 *
 * @see WhitespaceRelaxed for the relaxation itself
 * @see WhitespacePolicy for where relaxation must STOP
 * @see prefixLadder for the content-addressed prefix chain
 */
interface CacheKeyStrategy {
    /** Stable label — appears in telemetry so a hit can be attributed to a strategy. */
    val name: String

    /**
     * The cache identity for [canonicalJson], the exact bytes the mux would send.
     * Returning the same identity for two different requests asserts they may
     * share a reply.
     */
    fun identity(canonicalJson: String): String
}

/**
 * Byte-exact sha256 of the canonical request. The M3 invariant and the default.
 *
 * A 32-bit `String.hashCode` once stood here and two colliding requests returned
 * each other's payloads verbatim; the full ContentId is why that cannot recur.
 * This strategy can never yield a wrong reply, so it is always first in a
 * cascade and is the only one enabled unless an owner opts into more.
 */
object ExactContentId : CacheKeyStrategy {
    override val name = "exact"
    override fun identity(canonicalJson: String): String =
        ContentId.of(canonicalJson.encodeToByteArray()).value
}

/**
 * Where whitespace relaxation must STOP.
 *
 * "Relaxed whitespace" is only safe where whitespace carries no meaning. Inside
 * a fenced code block it carries all of it: collapsing the indentation of a
 * Python function or a YAML document does not produce "the same request with
 * tidier spacing", it produces a different program. Treating those two as one
 * cache identity would serve the answer to a question nobody asked.
 *
 * So relaxation runs over the request EXCEPT within preserved regions, each of
 * which can be switched off independently for owners who know their traffic.
 *
 * @property collapseRuns runs of spaces/tabs collapse to a single space
 * @property normalizeLineEndings CRLF and CR become LF before anything else
 * @property collapseBlankLines runs of blank lines collapse to one
 * @property trimTrailingSpace trailing spaces are dropped from each line
 * @property trimEdges leading/trailing whitespace of the whole payload is dropped
 * @property preserveFencedCode text inside ``` fences is byte-preserved
 * @property preserveInlineCode text inside `backticks` is byte-preserved
 * @property preserveIndentation each line's LEADING whitespace is byte-preserved
 *   even outside fences — the setting for indentation-significant traffic
 *   (YAML, Python, Makefiles) that arrives without fences to mark it
 */
data class WhitespacePolicy(
    val collapseRuns: Boolean = true,
    val normalizeLineEndings: Boolean = true,
    val collapseBlankLines: Boolean = true,
    val trimTrailingSpace: Boolean = true,
    val trimEdges: Boolean = true,
    val preserveFencedCode: Boolean = true,
    val preserveInlineCode: Boolean = true,
    val preserveIndentation: Boolean = false,
) {
    companion object {
        /**
         * Safe for prose-shaped traffic: everything relaxed, code preserved.
         * The one to reach for when prompts are assembled from templates whose
         * only instability is formatting.
         */
        val PROSE = WhitespacePolicy()

        /**
         * For traffic where leading whitespace is structural even outside
         * fences — config blobs, diffs, tabular text pasted inline.
         */
        val INDENT_SENSITIVE = WhitespacePolicy(preserveIndentation = true)

        /**
         * Relax nothing but line endings. The minimum useful relaxation: a
         * request that differs ONLY because it crossed a Windows boundary is
         * the same request, and almost nobody disputes that one.
         */
        val LINE_ENDINGS_ONLY = WhitespacePolicy(
            collapseRuns = false,
            collapseBlankLines = false,
            trimTrailingSpace = false,
            trimEdges = false,
        )
    }
}

/**
 * Normalizes whitespace under [policy], honouring the preservation boundaries.
 *
 * Single-pass and hand-rolled rather than a regex chain: the fence/inline-code
 * state is what decides whether a given space is meaningful, and that state is
 * not expressible as independent substitutions. Runs on the request path, so it
 * allocates one builder and walks the input once.
 */
object WhitespaceNormalizer {

    fun normalize(input: String, policy: WhitespacePolicy): String {
        val src = if (policy.normalizeLineEndings) {
            input.replace("\r\n", "\n").replace('\r', '\n')
        } else {
            input
        }

        val out = StringBuilder(src.length)
        var i = 0
        var inFence = false
        var inInline = false
        var atLineStart = true

        while (i < src.length) {
            val c = src[i]

            // ── fence toggling: ``` at any position flips block state ──
            if (policy.preserveFencedCode && c == '`' && src.startsWith("```", i)) {
                out.append("```")
                i += 3
                inFence = !inFence
                // Anything left on the opening line is the info string; it is
                // part of the fence and preserved verbatim by the branch below.
                atLineStart = false
                continue
            }

            if (inFence) {
                // Byte-preserved: this is the whole point of the boundary.
                out.append(c)
                if (c == '\n') atLineStart = true
                i++
                continue
            }

            // ── inline code: a single backtick toggles until the line ends ──
            if (policy.preserveInlineCode && c == '`') {
                inInline = !inInline
                out.append(c)
                i++
                atLineStart = false
                continue
            }
            if (inInline) {
                out.append(c)
                // An unterminated inline span must not swallow the rest of the
                // document — a newline closes it, as in Markdown.
                if (c == '\n') {
                    inInline = false
                    atLineStart = true
                }
                i++
                continue
            }

            // ── indentation boundary ──
            if (atLineStart && policy.preserveIndentation && (c == ' ' || c == '\t')) {
                while (i < src.length && (src[i] == ' ' || src[i] == '\t')) {
                    out.append(src[i]); i++
                }
                atLineStart = false
                continue
            }

            // ── horizontal whitespace ──
            if (c == ' ' || c == '\t') {
                var j = i
                while (j < src.length && (src[j] == ' ' || src[j] == '\t')) j++
                val runEndsLine = j < src.length && src[j] == '\n'
                val dropTrailing = policy.trimTrailingSpace && runEndsLine
                val dropLeading = policy.collapseRuns && atLineStart
                when {
                    dropTrailing || dropLeading -> Unit // emit nothing
                    policy.collapseRuns -> out.append(' ')
                    else -> out.append(src, i, j)
                }
                i = j
                continue
            }

            // ── vertical whitespace ──
            if (c == '\n') {
                if (policy.collapseBlankLines) {
                    var j = i
                    var newlines = 0
                    var lastNewline = i
                    // A "blank line" may carry spaces; skip across them too.
                    while (j < src.length && (src[j] == '\n' || src[j] == ' ' || src[j] == '\t')) {
                        if (src[j] == '\n') { newlines++; lastNewline = j }
                        j++
                    }
                    out.append(if (newlines > 1) "\n\n" else "\n")
                    // Resume just past the LAST newline, not past the whitespace
                    // that followed it: that whitespace is the next line's
                    // INDENTATION, and swallowing it here destroyed exactly what
                    // preserveIndentation exists to protect. Whitespace between
                    // the newlines is blank-line filler and is correctly consumed.
                    i = lastNewline + 1
                    atLineStart = true
                    continue
                }
                out.append(c)
                i++
                atLineStart = true
                continue
            }

            out.append(c)
            atLineStart = false
            i++
        }

        val result = out.toString()
        return if (policy.trimEdges) result.trim() else result
    }
}

/**
 * sha256 of the request after whitespace normalization under [policy].
 *
 * The trade this makes, stated plainly: two requests that differ ONLY in
 * whitespace outside the preserved regions will share a reply. That is the
 * intent. It is wrong for any caller whose prompts encode meaning in spacing
 * without fencing it — which is what [WhitespacePolicy.preserveIndentation] and
 * [WhitespacePolicy.LINE_ENDINGS_ONLY] exist to accommodate.
 *
 * The identity is namespaced by the policy, so changing the policy cannot
 * silently inherit entries written under the old one — a cache poisoned across
 * a policy change would be indistinguishable from a correctness bug.
 */
class WhitespaceRelaxed(
    private val policy: WhitespacePolicy = WhitespacePolicy.PROSE,
) : CacheKeyStrategy {
    override val name = "ws-relaxed"

    override fun identity(canonicalJson: String): String {
        val normalized = WhitespaceNormalizer.normalize(canonicalJson, policy)
        // Namespace: identity is (policy, normalized bytes), never bytes alone.
        val stamped = "${policyTag()}\u0000$normalized"
        return ContentId.of(stamped.encodeToByteArray()).value
    }

    private fun policyTag(): String = buildString {
        append("ws:")
        append(if (policy.collapseRuns) 'r' else '-')
        append(if (policy.normalizeLineEndings) 'l' else '-')
        append(if (policy.collapseBlankLines) 'b' else '-')
        append(if (policy.trimTrailingSpace) 't' else '-')
        append(if (policy.trimEdges) 'e' else '-')
        append(if (policy.preserveFencedCode) 'F' else '-')
        append(if (policy.preserveInlineCode) 'I' else '-')
        append(if (policy.preserveIndentation) 'N' else '-')
    }
}

/**
 * Consult several strategies in order.
 *
 * LOOKUP walks [strategies] in order and takes the first identity that hits, so
 * an exact hit is always preferred over a relaxed one — the cheapest correct
 * answer wins. STORE writes the payload under EVERY identity, so one paid call
 * warms the exact entry and each relaxed entry at once, and a later request that
 * differs only in formatting hits without a second purchase.
 *
 * [ExactContentId] is forced to the head whether or not the caller listed it.
 * A cascade whose first strategy is relaxed would answer a byte-identical repeat
 * from a relaxed bucket, making M3 unobservable and its guarantee untestable.
 */
class CacheCascade(strategies: List<CacheKeyStrategy>) {

    val strategies: List<CacheKeyStrategy> =
        (listOf(ExactContentId) + strategies.filter { it !is ExactContentId })
            .distinctBy { it.name }

    /** Identities to try on lookup, cheapest-and-safest first. */
    fun identities(canonicalJson: String): List<Pair<String, String>> =
        strategies.map { it.name to it.identity(canonicalJson) }

    /** The identity a receipt is attributed to: always the exact one. */
    fun primary(canonicalJson: String): String = ExactContentId.identity(canonicalJson)

    companion object {
        /** Exact only — the mux's behaviour before strategies existed. */
        val EXACT_ONLY = CacheCascade(emptyList())

        /** Exact, then prose-shaped whitespace relaxation. The usual choice. */
        val RELAXED = CacheCascade(listOf(WhitespaceRelaxed(WhitespacePolicy.PROSE)))

        /** Exact, then relaxation that keeps leading whitespace significant. */
        val RELAXED_INDENT_SAFE =
            CacheCascade(listOf(WhitespaceRelaxed(WhitespacePolicy.INDENT_SENSITIVE)))
    }
}

/**
 * The content-addressed PREFIX LADDER of a conversation.
 *
 * `[H(m0), H(m0,m1), H(m0,m1,m2), …]` — one ContentId per conversational
 * prefix, each covering every message up to that turn.
 *
 * This is deliberately NOT a reply-cache key: a reply to a three-turn
 * conversation is not a valid reply to its two-turn prefix, and any scheme that
 * treats it as one invents answers. What the ladder is for:
 *
 *  - CAS dedupe — a hundred conversations sharing one system prompt share one
 *    stored prefix blob instead of a hundred near-identical copies
 *  - provider prefix caching — the longest STABLE rung is the boundary worth
 *    marking for a provider that bills cached prefix tokens differently
 *    (the receipt's provider-measured cache counts are how you confirm it landed)
 *  - eviction order — rungs shared by many conversations are the last thing a
 *    store should drop
 *
 * [policy], when non-null, normalizes each message's text before hashing, so a
 * shared prefix is still recognised as shared after a re-wrap.
 */
fun prefixLadder(
    messages: Series<AcpMessage>,
    policy: WhitespacePolicy? = null,
): List<String> {
    val out = ArrayList<String>(messages.size)
    val acc = StringBuilder()
    for (i in 0 until messages.size) {
        val m = messages[i]
        val text = policy?.let { WhitespaceNormalizer.normalize(m.b, it) } ?: m.b
        // NUL-delimited so no role/text pair can be forged by concatenation:
        // ("ab","c") and ("a","bc") must not produce one identity.
        acc.append(m.a).append('\u0000').append(text).append('\u0001')
        out.add(ContentId.of(acc.toString().encodeToByteArray()).value)
    }
    return out
}

/**
 * The longest rung of [ladder] shared with [other], or -1 when they diverge at
 * the first turn. The index of the last common prefix message — the boundary a
 * provider prefix-cache hint should be placed at.
 */
fun sharedPrefixDepth(ladder: List<String>, other: List<String>): Int {
    var depth = -1
    val n = minOf(ladder.size, other.size)
    for (i in 0 until n) {
        if (ladder[i] != other[i]) break
        depth = i
    }
    return depth
}
