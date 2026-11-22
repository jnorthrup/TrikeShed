package borg.trikeshed.reactor.xet

import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.capability
import borg.trikeshed.lib.j
import borg.trikeshed.reactor.ReactorAction
import borg.trikeshed.reactor.ReactorEndpoint
import borg.trikeshed.reactor.ReactorResult

/**
 * Offline Xet endpoint. Requires [Capability.Cas]. Verbs (payload is raw file bytes):
 *  - `xet.chunk`    -> UTF-8 lines `offset<TAB>length<TAB>xetChunkHash<TAB>cid`
 *  - `xet.filehash` -> UTF-8 file hash in API string form
 * Failures answer with verb `error`.
 */
class XetReactorEndpoint : ReactorEndpoint {
    override suspend fun invoke(action: ReactorAction): ReactorResult {
        if (action.a.capability !is Capability.Cas) {
            return action.a j ("error" j "Invalid capability for XetReactorEndpoint".encodeToByteArray())
        }
        val verb = action.b.a
        val data = action.b.b
        return when (verb) {
            VERB_CHUNK -> {
                val text = XetIpfsBridge.bridge(data).chunks.joinToString("\n") {
                    "${it.offset}\t${it.length}\t${it.xetChunkHash}\t${it.cid}"
                }
                action.a j (verb j text.encodeToByteArray())
            }
            VERB_FILE_HASH -> action.a j (verb j XetHashing.toApiHex(XetHashing.fileHash(data)).encodeToByteArray())
            else -> action.a j ("error" j "Unsupported verb: $verb".encodeToByteArray())
        }
    }

    companion object {
        const val VERB_CHUNK = "xet.chunk"
        const val VERB_FILE_HASH = "xet.filehash"
    }
}
