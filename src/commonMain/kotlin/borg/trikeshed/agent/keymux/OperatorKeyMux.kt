package borg.trikeshed.agent.keymux

import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations

/**
 * The operator credential lane — ONE recipe, every caller.
 *
 * The daemon built this inline and every other entrypoint built its own
 * approximation, so "the daemon can't see my key" and "the CLI says the key is
 * there" were both true at once and neither one was a lie. A diagnostic that
 * resolves through a DIFFERENT chain than the thing it is diagnosing reports
 * nothing at all; that is the defect this function exists to make impossible.
 *
 * Lane order is precedence order (first wins, [borg.trikeshed.agent.keymux.FirstWinsResolver]):
 *
 *  1. [borg.trikeshed.agent.keymux.HarnessSource] — conventional process env names (`OPENAI_API_KEY`, …).
 *  2. [borg.trikeshed.agent.keymux.EnvSource] — the legacy raw-env lane (`LLM_OPENAI_KEY` shape).
 *  3. [borg.trikeshed.agent.keymux.HermesCredentialSource] — hermes' OWN pool (`credential_pool`:
 *     priority-ordered, with cooldowns, following its `env:<VAR>` indirection).
 *  4. [borg.trikeshed.agent.keymux.HarnessSource] — remaining dotenv and harness credentials. A pool's
 *     HTTP exhaustion cannot be bypassed by rereading its same dotenv token.
 *
 * The pool is read on every resolution. Remaining harness files carry [ttlMs];
 * process env uses [borg.trikeshed.agent.keymux.CachedKeySource]'s default.
 *
 * [fileOps] must be passed explicitly. Both file lanes degrade SILENTLY to
 * env-only when no [FileOperations] rides the coroutine context, and the boot
 * paths that build a KeyMux are not coroutine contexts that carry one.
 */
fun operatorKeyMux(
    fileOps: FileOperations,
    hermesHome: String = borg.trikeshed.agent.keymux.defaultHermesHome(),
    ttlMs: Long = 5 * 60_000L,
): borg.trikeshed.agent.keymux.KeyMux = borg.trikeshed.agent.keymux.KeyMux {
    cached("*", borg.trikeshed.agent.keymux.HarnessSource(processEnvOnly = true))
    cached("*", borg.trikeshed.agent.keymux.EnvSource())
    // Uncached on purpose: a key rotated in auth.json or .env is used on the
    // very next call. The source itself re-reads its files per call.
    bind("llm.*.*", borg.trikeshed.agent.keymux.HermesCredentialSource(hermesHome, fileOps))
    cached(
        "*",
        borg.trikeshed.agent.keymux.HarnessSource(
            explicitFileOps = fileOps,
            hermesHomeOverride = hermesHome
        ),
        ttlMs = ttlMs
    )
}

/**
 * `$HERMES_HOME`, else `~/.hermes`.
 *
 * Explicit rather than defaulted to `~/.hermes` at the call site: a profile
 * operator's keys live under their `$HERMES_HOME`, and a hardcoded `~/.hermes`
 * reads an empty directory and reports "no keys" with total confidence.
 * `FileOperations.resolvePath` expands the `~`.
 */
fun defaultHermesHome(getenv: (String) -> String? = { SystemOperations.default.getenv(it) }): String =
    getenv("HERMES_HOME")?.takeIf { it.isNotBlank() } ?: "~/.hermes"
