package borg.trikeshed.job

import borg.trikeshed.couch.isam.DurableAppendLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import borg.trikeshed.couch.isam.WalFrame

/**
 * Result of recovering from the JobRepository log.
 */
data class RecoveryResult(
    val committedSequence: Long,
    private val snapshots: Map<JobId, JobSnapshot>
) {
    fun snapshot(jobId: JobId): JobSnapshot? = snapshots[jobId]
    fun snapshot(jobIdStr: String): JobSnapshot? = snapshots[JobId.of(jobIdStr)]
}

/**
 * Manages durability of Job states using an append-only log.
 */
class JobRepository(
    private val log: DurableAppendLog,
    private val casStore: CasStore
) {
    private var currentSequence: Long = 0L

    /**
     * Commits a job snapshot to the log.
     */
    suspend fun commit(jobId: JobId, snapshot: JobSnapshot, payload: ByteArray) {
        currentSequence++
        val snapshotJson = Json.encodeToString(snapshot)
        val combinedPayload = (snapshotJson + "|||" + payload.decodeToString()).encodeToByteArray()

        log.append(currentSequence, combinedPayload)
        log.flush()
    }

    // For test compatibility where jobid is passed as string
    suspend fun commit(jobIdStr: String, snapshot: JobSnapshot, payload: ByteArray) {
        commit(JobId.of(jobIdStr), snapshot, payload)
    }

    /**
     * Recovers state by replaying the log.
     * Verifies referenced CAS blobs before publishing heads.
     */
    suspend fun recover(): RecoveryResult {
        val snapshots = mutableMapOf<JobId, JobSnapshot>()

        val lastSeq = log.replay { sequence, combinedPayload ->
            currentSequence = sequence

            try {
                val combinedStr = combinedPayload.decodeToString()
                val parts = combinedStr.split("|||")
                if (parts.size >= 2) {
                    val snapshotJson = parts[0]
                    val snapshot = Json.decodeFromString<JobSnapshot>(snapshotJson)

                    // Verifies CAS blobs before publishing heads (using causalKey as the CAS reference)
                    val causalKeyExists = casStore.contains(snapshot.causalKey)

                    // Also check any dependencies just in case they represent CAS blobs
                    var depsExist = true
                    for (dep in snapshot.dependencies) {
                        if (!casStore.contains(dep.value)) {
                            depsExist = false
                            break
                        }
                    }

                    if (causalKeyExists && depsExist) {
                        snapshots[snapshot.jobId] = snapshot
                    } else {
                        // Drop frame from heads if CAS blob is missing
                    }
                }
            } catch (e: Exception) {
                // Ignore parse errors from corrupted data during tests
            }
        }

        return RecoveryResult(lastSeq, snapshots)
    }

    /**
     * Inject corruption for testing torn WAL.
     */
    fun injectCorruptionAfter(sequence: Long) {
        log.injectCorruptionAfter(sequence)
    }
}
