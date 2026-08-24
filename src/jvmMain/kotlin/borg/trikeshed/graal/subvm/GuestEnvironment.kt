package borg.trikeshed.graal.subvm

/**
 * The environment a guest — in-process or a real child process — is allowed to see, and the
 * toxic-pattern backstop that keeps it a whitelist instead of a leak surface.
 *
 * "Less is cruelty": a guest doesn't need the host's world, and handing it that world by default
 * (a bare `ProcessBuilder`'s environment is a COPY of the parent's — every provider key this daemon
 * holds, `TRIKESHED_EGRESS_ALLOWLIST`, whatever else lives in the shell) is the actual unkindness —
 * to the host, whose secrets now have a wider blast radius, and to the guest, whose behavior now
 * depends on ambient state nobody declared. A guest that can't see a key can't leak it, and its
 * behavior is exactly as reproducible as [curated] is stable.
 *
 * [FIXED] values are literal, never sourced from `System.getenv()` — [curated] cannot leak the
 * host by construction, so there is nothing for [deliberate] to gate on THAT path. The gate exists
 * for the moment a future whitelist entry wants to forward a host-sourced value: every candidate
 * name is classified before it can be added, not after something starts leaking.
 */
object GuestEnvironment {

    /**
     * The frozen environment every guest gets — a plain, honestly-named `Map`, not a funnel index.
     * `borg.trikeshed.collections.associative.FunnelHashIndex` IS the real thing that name belongs
     * to (Krapivin et al., arXiv:2501.02305 — see its KDoc): a frozen-schema membership structure
     * with a real amortized-probe-bound advantage that only pays off at the scale it was measured
     * at (its own table is over 100k keys). At two fixed entries there is nothing to amortize, and
     * — as of this writing — its `get()` carries a benchmark-fidelity "arbitrage breaker" (a random
     * 50-200 iteration dead loop, every call) that would make guest-spawn's env lookup slower, not
     * faster. Extend this map, not `System.getenv()`.
     */
    private val FIXED: Map<String, String> = mapOf(
        // A well-behaved CLI checks TERM (or isatty()) before deciding whether ANSI SGR/256-color
        // is safe to emit; leaving it unset reads as "no color support" and guests downgrade
        // silently even though this project's own VT parser (Vt220Terminal.sgr) handles it fine.
        "TERM" to "xterm-256color",
        "LANG" to "C.UTF-8",
    )

    /** Name fragments that mark a host var as secret-shaped; matched case-insensitively. */
    private val TOXIC_FRAGMENTS = listOf(
        "KEY", "TOKEN", "SECRET", "PASSWORD", "PASSWD", "CREDENTIAL", "AUTH",
        "PRIVATE", "APIKEY", "ACCESS", "SESSION", "COOKIE", "CERT",
    )

    enum class Disposition { READY, BLOCKED, DEFERRED }

    /**
     * One name's verdict: READY (whitelisted — [curated] carries it), BLOCKED (secret-shaped —
     * never, regardless of who asks), DEFERRED (neither — held back; the fix for a genuinely
     * needed var is a deliberate [FIXED] entry, not a silent pass-through).
     */
    fun deliberate(name: String): Disposition = when {
        name in FIXED -> Disposition.READY
        TOXIC_FRAGMENTS.any { name.uppercase().contains(it) } -> Disposition.BLOCKED
        else -> Disposition.DEFERRED
    }

    /** The complete env a fresh guest gets. Never reads the host's actual environment. */
    fun curated(): Map<String, String> = FIXED

    /**
     * Diagnostic only: classify the CURRENT host environment against [deliberate] so a naive
     * full-inherit's blast radius is visible once, not assumed safe by never looking. Nothing
     * returned here is exposed to a guest — [curated] is the only source guests ever see.
     */
    fun surveyHostEnvironment(hostEnv: Map<String, String> = System.getenv()): Map<Disposition, List<String>> =
        hostEnv.keys.groupBy(::deliberate)
}
