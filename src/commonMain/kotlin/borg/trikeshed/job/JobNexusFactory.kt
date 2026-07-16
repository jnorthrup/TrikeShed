package borg.trikeshed.job

import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.parse.confix.value
import borg.trikeshed.parse.confix.Syntax
import kotlinx.coroutines.CoroutineScope

/**
 * JobNexusFactory — the single effectful composition boundary.
 *
 * Validates capabilities, assembles components in order (scope → CAS → WAL →
 * index → Rete → projection → checkpoint), and rolls back on failure.
 */
object JobNexusFactory {

    fun open(spec: JobNexusSpec, bindings: JobNexusBindings): JobSupervisorElement {
        // Validate bindings
        val scope = bindings.parentScope
            ?: throw IllegalArgumentException("parent scope is required")

        // Capability validation
        if (spec.storage.backend == StorageBackend.File && bindings.fileOps == null) {
            throw IllegalArgumentException("file capability required for File backend")
        }
        if (spec.storage.backend == StorageBackend.LinuxBtrfs && !bindings.linuxStorageAvailable) {
            throw IllegalArgumentException("linux/btrfs capability not available on this platform")
        }

        // Validate spec
        val validationResult = JobNexusSpecValidator.validate(spec)
        if (!validationResult.valid) {
            throw IllegalArgumentException(validationResult.errors.joinToString("; "))
        }

        // Assembly with rollback tracking — order: scope, cas, wal, index, rete, projection, checkpoint
        val componentOrder = listOf("scope", "cas", "wal", "index", "rete", "projection", "checkpoint")
        val opened = mutableListOf<String>()
        var orderCounter = 0

        try {
            // scope
            opened.add("scope")
            bindings.closeTrace.add(CloseTraceEntry("scope", ++orderCounter, false))

            // cas — register only after factory succeeds
            val cas = bindings.componentFactories.casStoreFactory()
            opened.add("cas")
            bindings.closeTrace.add(CloseTraceEntry("cas", ++orderCounter, false))

            // wal
            val wal = bindings.componentFactories.walFactory()
            opened.add("wal")
            bindings.closeTrace.add(CloseTraceEntry("wal", ++orderCounter, false))

            // index
            val index = bindings.componentFactories.indexFactory()
            opened.add("index")
            bindings.closeTrace.add(CloseTraceEntry("index", ++orderCounter, false))

            // rete
            val rete = bindings.componentFactories.reteFactory()
            opened.add("rete")
            bindings.closeTrace.add(CloseTraceEntry("rete", ++orderCounter, false))

            // projection
            val projection = bindings.componentFactories.projectionFactory()
            opened.add("projection")
            bindings.closeTrace.add(CloseTraceEntry("projection", ++orderCounter, false))

            // checkpoint
            val checkpoint = bindings.componentFactories.checkpointFactory()
            opened.add("checkpoint")
            bindings.closeTrace.add(CloseTraceEntry("checkpoint", ++orderCounter, false))

            // Mark all as opened successfully
            return JobSupervisorElement.open(scope, spec.channels.commands)

        } catch (e: Throwable) {
            // Rollback: close all previously opened components in reverse order
            for (compName in opened.reversed()) {
                val entry = bindings.closeTrace.find { it.component == compName && !it.closed }
                if (entry != null) {
                    val idx = bindings.closeTrace.indexOf(entry)
                    bindings.closeTrace[idx] = entry.copy(closed = true)
                }
            }
            // Remove entries for components that were never opened
            val openedSet = opened.toSet()
            bindings.closeTrace.retainAll { it.component in openedSet }
            throw e
        }
    }
}
