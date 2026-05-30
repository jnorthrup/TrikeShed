@file:Suppress("unused")

package borg.trikeshed.og1.fanout

import borg.trikeshed.og1.types.*

/* ═════════════════════════════════════════════════════════════════════
 *  FanoutPlan — delivery fan-out with wireproto payload pool.
 *
 *  Python payloads are stored as a StringPool — constant-sized index
 *  references. No inline triple-quoted strings. Pool is decoded on
 *  demand by PyEngine.eval(pool[id]).
 * ═════════════════════════════════════════════════════════════════════ */

/* ── Wireproto String Pool ─────────────────────────────────────────── *
 *
 * Constant-sized index into a shared string table.
 * Payloads are decoded lazily — no string allocation until accessed.
 * Each slot is a fixed-size entry: [offset: Int, length: Int].
 */

object WirePool {
    /** Wire-format payload slots. Key = payload id, value = (offset, length) in shared buffer. */
    private val slots = mutableMapOf<String, Long>()

    /** Shared string table — concatenated UTF-8 payloads, delimited by \u0000. */
    private val buffer = StringBuilder()

    /** Register a payload, return its wire key. */
    fun register(id: String, payload: String): String {
        val offset = buffer.length
        buffer.append(payload)
        buffer.append('\u0000')
        slots[id] = ((offset.toLong()) shl 32) or (payload.length.toLong())
        return id
    }

    /** Decode a payload by wire key. */
    fun decode(id: String): String? {
        val wire = slots[id] ?: return null
        val offset = (wire shr 32).toInt()
        val length = (wire and 0xFFFFFFFFL).toInt()
        return buffer.substring(offset, offset + length)
    }

    /** All registered keys. */
    val keys: Set<String> get() = slots.keys.toSet()

    /** Pool size in bytes. */
    val sizeBytes: Int get() = buffer.length * 2  // UTF-16 code units × 2 bytes
}

/* ── Payload Registration ──────────────────────────────────────────── */

/** Wire keys for all CRMS algebraic payloads. Constant-sized field references. */
object Payloads {
    const val WORKER_GOAL      = "worker_goal"
    const val CRITIC_GOAL      = "critic_goal"
    const val CRITIC_PARSER    = "critic_parser"
    const val KMEANS_INIT      = "kmeans_init"
    const val KMEANS_ITER      = "kmeans_iter"
    const val QUORUM           = "quorum"
    const val DEBT_TRIAGE      = "debt_triage"
    const val PANEL_VOTE       = "panel_vote"
    const val GAP_ANALYSIS     = "gap_analysis"
    const val BRAINSTORM       = "brainstorm"
    const val LEAN             = "lean"
    const val DELEGATE         = "delegate"

    /** Resolve a wire key to its payload string. */
    operator fun get(id: String): String = WirePool.decode(id) ?: error("no payload: $id")
}

/* ── Static pool initialization ────────────────────────────────────── */

private fun initPool() {
    WirePool.register(Payloads.WORKER_GOAL, """
def build_worker_goal(task, previous_output, feedback):
    if not previous_output:
        return f"TASK:\n{task}\n\nImplement now. Write complete code. No TODOs.\nReturn JSON: summary, locations, tests, code."
    return f"TASK:\n{task}\n\nPREVIOUS:\n{previous_output}\n\nCRITIC FEEDBACK:\n{feedback or ''}\n\nEdit. Do not regress."
""".trimIndent())

    WirePool.register(Payloads.CRITIC_GOAL, """
def build_critic_goal(task, worker_result):
    return f"TASK:\n{task}\n\nWORKER:\n{worker_result}\n\nOutput ONLY JSON. verdict=COMPLETE|EDIT|RESTART."
""".trimIndent())

    WirePool.register(Payloads.CRITIC_PARSER, """
import re, json
VERDICT_KINDS = {"COMPLETE", "EDIT", "RESTART"}
def extract_json(text):
    text = re.sub(r'^```(?:json)?\s*', '', text.strip())
    text = re.sub(r'\s*```\s*$', '', text)
    m = re.search(r'\{.*\}', text, re.DOTALL)
    return json.loads(m.group()) if m else {}
def parse_critic_verdict(output):
    p = extract_json(output)
    v = str(p.get("verdict", "EDIT")).upper()
    return {"verdict": v if v in VERDICT_KINDS else "EDIT",
            "summary": p.get("summary", ""), "feedback": p.get("feedback", ""),
            "demands": list(p.get("demands", [])), "score": p.get("score"),
            "validated_locations": list(p.get("validated", [])),
            "rejected_locations": list(p.get("rejected", [])), "raw": output}
""".trimIndent())

    WirePool.register(Payloads.KMEANS_INIT, """
import random, math
def kmeans_init(points, k):
    return random.sample(points, min(k, len(points)))
""".trimIndent())

    WirePool.register(Payloads.KMEANS_ITER, """
import math
def kmeans_assign(points, centroids):
    clusters = {i: [] for i in range(len(centroids))}
    for p in points:
        nearest = min(range(len(centroids)), key=lambda i: sum((a-b)**2 for a,b in zip(p, centroids[i])))
        clusters[nearest].append(p)
    return clusters
def kmeans_update(clusters):
    return [tuple(sum(c)/len(pts) for c in zip(*pts)) if pts else (0,)*len(clusters[0])
            for pts in clusters.values()]
""".trimIndent())

    WirePool.register(Payloads.QUORUM, """
def quorum_vote(votes, threshold=0.6):
    totals = {}
    for v in votes:
        totals[v["rule"]] = totals.get(v["rule"], 0.0) + v["confidence"]
    best = max(totals, key=totals.get)
    return {"winner": best, "confidence": totals[best] / len(votes), "quorum": totals[best] / len(votes) >= threshold}
""".trimIndent())

    WirePool.register(Payloads.DEBT_TRIAGE, """
def debt_triage(items):
    return sorted(items, key=lambda x: x.get("severity", 0), reverse=True)
""".trimIndent())

    WirePool.register(Payloads.PANEL_VOTE, """
def panel_vote(facets, weights):
    scores = {}
    for facet, vote in zip(facets, weights):
        for rule, conf in vote.items():
            scores[rule] = scores.get(rule, 0.0) + conf * facet.get("weight", 1.0)
    return sorted(scores.items(), key=lambda x: -x[1])
""".trimIndent())

    WirePool.register(Payloads.GAP_ANALYSIS, """
def gap_analysis(items, baseline):
    gaps = []
    for item in items:
        coverage = sum(1 for k in baseline if k in item) / max(len(baseline), 1)
        gaps.append({"id": item.get("id"), "coverage": coverage, "missing": [k for k in baseline if k not in item]})
    return sorted(gaps, key=lambda x: x["coverage"])
""".trimIndent())

    WirePool.register(Payloads.BRAINSTORM, """
def brainstorm(topic, branches):
    return [{"scope": b, "questions": [f"investigate {b} for {topic}"]} for b in branches]
""".trimIndent())

    WirePool.register(Payloads.LEAN, """
def lean_reduce(code):
    lines = code.split('\n')
    return '\n'.join(l for l in lines if l.strip() and not l.strip().startswith('#'))
""".trimIndent())

    WirePool.register(Payloads.DELEGATE, """
def delegate(task, quota):
    return {"task": task, "budget": quota, "status": "delegated"}
""".trimIndent())
}

/** Ensure pool is initialized. Idempotent. */
val poolInit: Unit by lazy { initPool() }
