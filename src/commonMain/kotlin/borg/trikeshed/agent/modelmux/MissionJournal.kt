package borg.trikeshed.agent.modelmux

import borg.trikeshed.lib.*
import borg.trikeshed.parse.jsonOf
import borg.trikeshed.userspace.nio.file.spi.*

/**
 * Single-owner per-run receipt journal. Atomic durable publication goes through
 * commonMain's filesystem SPI. Not a multi-writer chronicle or a resume engine.
 */
class MissionJournal(val files: FileOperations, val path: String) : AutoCloseable {
    val lease: FileLease
    val records = mutableListOf<String>()
    var closed = false
        private set

    init {
        require(files.durability == StorageDurability.DURABLE) { "Mission journal requires durable filesystem publication" }
        lease = files.acquireExclusiveLease("$path.lock")
        try { require(!files.exists(path)) { "Mission journal already exists; refusing overwrite" } }
        catch (failure: Throwable) { lease.close(); throw failure }
    }

    fun record(event: borg.trikeshed.agent.modelmux.MissionEvent) {
        check(!closed) { "Mission journal is closed" }
        val row = jsonOf(mapOf("sequence" to records.size + 1, "task" to event.task,
            "phase" to event.phase.name, "claim_id" to event.claimId, "worker" to event.worker,
            "type" to event.role.name.lowercase(), "targets" to event.targets.view.toList()))
        val next = (records + row).joinToString("\n", postfix = "\n")
        files.writeAtomically(path, next.encodeToByteArray())
        records.add(row)
    }

    override fun close() {
        if (!closed) { closed = true; lease.close() }
    }
}
