package borg.trikeshed.util.oroboros

import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single ingress channel for coordinating mutations across Storage, Version, Couch, and Network lanes.
 * Idempotent replay handling via (agent, path, ContentId).
 */
class OroborosCoordinator(
    private val agent: String,
    private val forgeHome: String,
    private val gateway: OroborosGateway,
    private val fileOps: FileOperations,
    capacity: Int = 64
) {
    private val ingressChannel = Channel<Mutation>(capacity)
    private val resultsChannel = Channel<Result<CommittedMutation>>(capacity)
    private val seqMutex = Mutex()
    private var currentSeq = 0L

    val results: ReceiveChannel<Result<CommittedMutation>> get() = resultsChannel

    suspend fun submit(mutation: Mutation) {
        ingressChannel.send(mutation)
    }

    suspend fun drain() {
        ingressChannel.close()
        for (mutation in ingressChannel) {
            val result = processMutation(mutation)
            resultsChannel.send(result)
        }
        resultsChannel.close()
    }

    private suspend fun processMutation(mutation: Mutation): Result<CommittedMutation> {
        return try {
            val storage = gateway.storage
            val version = gateway.version
            val casStore = storage[OroborosStorageK.Cas]
            val attachments = storage[OroborosStorageK.Attachments]

            // 1. Storage / CAS / File
            var cid: ContentId? = null
            var actionStr = ""
            val fullPath = fileOps.resolvePath(forgeHome, mutation.path)

            when (mutation) {
                is Mutation.Upsert -> {
                    actionStr = "Upsert"
                    // Verify if idempotent
                    val existingPair = attachments.getAttachment(mutation.path)
                    if (existingPair != null) {
                        val expectedCid = ContentId.of(mutation.bytes)
                        if (existingPair.first.agentId == agent && existingPair.first.contentId == expectedCid) {
                            // Idempotent hit: return success without advancing sequence
                            return Result.success(
                                CommittedMutation(
                                    seq = existingPair.first.sequence,
                                    agent = agent,
                                    path = mutation.path,
                                    cid = expectedCid,
                                    action = actionStr,
                                    revision = existingPair.first.revision
                                )
                            )
                        }
                    }

                    cid = casStore.put(mutation.bytes)
                    // Create parent directories if needed
                    val parent = getParent(fullPath)
                    if (parent != null && !fileOps.exists(parent)) {
                        fileOps.mkdirs(parent)
                    }
                    fileOps.write(fullPath, mutation.bytes)
                }
                is Mutation.Delete -> {
                    actionStr = "Delete"
                    // Verify if idempotent
                    val existingPair = attachments.getAttachment(mutation.path)
                    if (existingPair == null) {
                        // Idempotent delete (already deleted) - we don't have seq, fake 0 or get last known.
                        // For this implementation, we return success.
                        return Result.success(
                            CommittedMutation(
                                seq = 0,
                                agent = agent,
                                path = mutation.path,
                                cid = null,
                                action = actionStr,
                                revision = ""
                            )
                        )
                    }

                    if (fileOps.exists(fullPath)) {
                        fileOps.deleteRecursively(fullPath)
                    }
                }
            }

            // 2. Version Gateway (Git / Pijul)
            val msg = "$actionStr ${mutation.path} by $agent"
            val revision = version.record(forgeHome, agent, msg)
                ?: throw IllegalStateException("Version gateway failed to record change")

            // 3. Couch Attachment (Storage Lane)
            val seq = seqMutex.withLock {
                currentSeq++
                currentSeq
            }

            when (mutation) {
                is Mutation.Upsert -> {
                    val ref = OroborosAttachmentRef(
                        path = mutation.path,
                        contentType = mutation.contentType,
                        length = mutation.bytes.size.toLong(),
                        contentId = cid!!,
                        agentId = agent,
                        revision = revision,
                        sequence = seq
                    )
                    attachments.putAttachment(ref, mutation.bytes)
                }
                is Mutation.Delete -> {
                    attachments.deleteAttachment(mutation.path, revision)
                }
            }

            // 4. Network publication would happen here, potentially via event bus or explicitly.
            // In the context of OroborosNetworkRow, we would use fanout or similar, but for now
            // we assume durability means success.

            Result.success(
                CommittedMutation(
                    seq = seq,
                    agent = agent,
                    path = mutation.path,
                    cid = cid,
                    action = actionStr,
                    revision = revision
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun getParent(path: String): String? {
        val lastSlash = path.lastIndexOf('/')
        return if (lastSlash > 0) path.substring(0, lastSlash) else null
    }
}
