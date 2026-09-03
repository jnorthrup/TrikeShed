package keymux

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
 * Lane order is precedence order (first wins, [FirstWinsResolver]):
 *
 *  1. [HarnessSource] — conventional env names (`OPENAI_API_KEY`, …) plus the
 *     harnesses' own credential stores: hermes `.env`/profiles, `~/.codex/auth.json`,
 *     opencode's `auth.json`. A key the operator already gave another tool answers
 *     here without a second copy.
 *  2. [EnvSource] — the legacy raw-env lane (`LLM_OPENAI_KEY` shape).
 *  3. [HermesCredentialSource] — hermes' OWN pool (`credential_pool`:
 *     priority-ordered, with cooldowns, following its `env:<VAR>` indirection).
 *     This is the BORROWING lane and it ranks last on purpose: an env key the
 *     operator set for this process must always outrank a pool row.
 *
 * The two file-backed lanes carry a short TTL ([ttlMs], default 5 minutes) so
 * pool rotation and exhaustion cooldowns are picked up within a coffee break;
 * the env lane keeps the [CachedKeySource] default, since process env cannot
 * change under a running JVM.
 *
 * [fileOps] must be passed explicitly. Both file lanes degrade SILENTLY to
 * env-only when no [FileOperations] rides the coroutine context, and the boot
 * paths that build a KeyMux are not coroutine contexts that carry one.
 */
fun operatorKeyMux(
    fileOps: FileOperations,
    hermesHome: String = defaultHermesHome(),
    ttlMs: Long = 5 * 60_000L,
): KeyMux = KeyMux {
    cached("*", HarnessSource(explicitFileOps = fileOps), ttlMs = ttlMs)
    cached("*", EnvSource())
    // Uncached on purpose: a key rotated in auth.json or .env is used on the
    // very next call. The source itself re-reads its files per call.
    bind("llm.*.*", HermesCredentialSource(hermesHome, fileOps))
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
