package borg.trikeshed.ccek

import borg.trikeshed.context.ElementState
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext

/**
 * Result of CCEK scope validation — shows what was provided vs. what was required.
 */
data class CcekScopeValidation(
    /** The SupervisorJob that enforces child-failure isolation. */
    val supervisorJob: CompletableJob,
    /** Element keys found in coroutine context. */
    val providedKeys: Set<CoroutineContext.Key<*>>,
    /** Element keys that were required but absent. */
    val missingKeys: Set<CoroutineContext.Key<*>>,
    /** SPI names found in NioSupervisor service registry. */
    val providedSpis: Set<String>,
    /** SPI names that were required but absent. */
    val missingSpis: Set<String>,
) {
    val isValid: Boolean get() = missingKeys.isEmpty() && missingSpis.isEmpty()
}

/**
 * Assert that the current coroutine scope has the minimum CCEK invariants needed
 * to safely launch library operations.
 *
 * Call this at the top of any library entry point (main, gateway, etc.) that
 * delegates to NioSupervisor, FileOperations, or any CCEK-aware SPI.
 *
 * @param requiredKeys Element keys that MUST be present in the coroutine context.
 *                     Pass any [CoroutineContext.Key] whose associated element must exist.
 * @param minimumSpis  SPI names that MUST be registered in NioSupervisor.
 *                     Names are derived from `CoroutineContext.Element.key.toString()`.
 *
 * @throws IllegalStateException if the coroutine context has no Job or the Job is not
 *                               a CompletableJob (SupervisorJob).
 * @throws IllegalArgumentException if any required key is absent from the context.
 */
suspend fun requireCcekScope(
    vararg requiredKeys: CoroutineContext.Key<*>,
    minimumSpis: Set<String> = emptySet(),
): CcekScopeValidation {
    val ctx = currentCoroutineContext()

    // ── Job / SupervisorJob enforcement ──────────────────────────────────
    // Walk the Job parent chain to find a CompletableJob (SupervisorJob).
    // withContext/UndispatchedCoroutine wraps ctx[Job] but the SupervisorJob
    // is always reachable as ctx[Job] or one of its ancestors.
    val job = ctx[Job] ?: error(
        "CCEK enforcement failed: no Job in coroutineContext. " +
            "Call requireCcekScope() inside a coroutine scope."
    )
    fun Job.findCompletable(): CompletableJob? {
        if (this is CompletableJob) return this
        return parent?.findCompletable()
    }
    val supervisorJob = job.findCompletable() ?: error(
        "CCEK enforcement failed: no CompletableJob found in Job parent chain (root is ${job::class.simpleName}). " +
            "Use SupervisorJob() as a parent in your scope."
    )

    // ── Element.Key enforcement ────────────────────────────────────────────
    // Collect all keys from context elements for comparison.
    val providedKeys: Set<CoroutineContext.Key<*>> = ctx.fold(emptySet()) { acc, element ->
        acc + element.key
    }
    val missingKeys = requiredKeys.filter { required ->
        providedKeys.none { it.toString() == required.toString() }
    }.toSet()

    if (missingKeys.isNotEmpty()) {
        val missingNames = missingKeys.joinToString { "'${it}'" }
        error(
            "CCEK enforcement failed: missing required Element.Key(s): $missingNames. " +
                "Register them in your CoroutineScope before launching this operation."
        )
    }

    // ── SPI enforcement ────────────────────────────────────────────────────
    // Look up NioSupervisor in context; enumerate its registered services.
    val nioSupervisor = ctx[NioSupervisor.Key]
    val registeredSpiNames = nioSupervisor?.services
        ?.flatMap { service ->
            buildList {
                add(service.key.toString())
                add(service::class.simpleName.orEmpty())
                if (service is FileOperations) {
                    add("FileOperations")
                    add(FileOperations.Key.toString())
                }
            }
        }
        ?.filter { it.isNotBlank() }
        ?.toSet()
        ?: emptySet()

    val providedSpis: Set<String> = registeredSpiNames
    val missingSpis: Set<String> = if (minimumSpis.isEmpty()) emptySet() else minimumSpis - providedSpis

    if (missingSpis.isNotEmpty()) {
        error(
            "CCEK enforcement failed: missing required SPI(s): ${missingSpis.joinToString()}. " +
                "Register them via NioSupervisor.open() before launching this operation."
        )
    }

    return CcekScopeValidation(
        supervisorJob = supervisorJob,
        providedKeys = providedKeys,
        missingKeys = missingKeys,
        providedSpis = providedSpis,
        missingSpis = missingSpis,
    )
}

/**
 * Convenience overload that validates only the SupervisorJob and Key invariants,
 * without requiring any specific SPIs.
 */
suspend fun requireCcekScope(vararg requiredKeys: CoroutineContext.Key<*>): CcekScopeValidation =
    requireCcekScope(requiredKeys = requiredKeys, minimumSpis = emptySet())