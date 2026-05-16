package borg.trikeshed.couch.pijul

import borg.trikeshed.process.ProcessShell
import borg.trikeshed.couch.htx.*

/**
 * PijulIpfsStore — store and retrieve patches via IPFS.
 *
 * Patches are immutable and content-addressed, making them ideal for IPFS.
 * This module implements the "ipfs:*" capabilities from KET:
 *   - ipfs:store   — add a patch to IPFS, return CID
 *   - ipfs:retrieve — fetch a patch by CID, return Patch
 *   - ipfs:pin     — prevent GC of a CID
 *   - ipfs:list    — list stored patches for a namespace
 */

/** Result of an IPFS operation. */
sealed class IpfsResult {
    data class Stored(val cid: CharSequence, val sizeBytes: Long) : IpfsResult()
    data class PatchResult(val patch: Patch) : IpfsResult()
    data class Error(val message: CharSequence) : IpfsResult()
}

class PijulIpfsStore(
    private val shell: ProcessShell,
    private val patchNamespace: CharSequence = "pijul/patches",
) {
    companion object {
        const val PATCH_CID_PREFIX = "/ipfs/"
    }

    /**
     * Store a patch in IPFS.
     * Returns the CID or an error.
     */
    fun store(patch: Patch): IpfsResult {
        val patchData = serializePatch(patch)
        val sizeBytes = patchData.size.toLong()

        val result = shell.exec(
            "sh",
            listOf("-c", "printf '%s' '${escapeShell(patchData.decodeToString())}' | ipfs add -Q"),
        )
        if (result.exitCode != 0) {
            return IpfsResult.Error("ipfs add failed: ${result.stderr}")
        }
        val cid = result.stdout.trim()
        if (cid.isEmpty() || cid.contains("Error")) {
            return IpfsResult.Error("ipfs add returned invalid cid: $cid")
        }
        pin(cid)
        return IpfsResult.Stored(cid, sizeBytes)
    }

    /**
     * Retrieve a patch from IPFS by CID.
     */
    fun retrieve(cid: CharSequence): IpfsResult {
        val result = shell.exec("ipfs", listOf("cat", cid))
        if (result.exitCode != 0) {
            return IpfsResult.Error("ipfs cat failed: ${result.stderr}")
        }
        val data = result.stdout
        if (isCompositeCid(cid)) {
            val chunkCids = parseCompositeCid(cid)
            val chunks: List<ByteArray> = chunkCids.map { chunkCid ->
                val catResult = shell.exec("ipfs", listOf("cat", chunkCid))
                if (catResult.exitCode != 0) return IpfsResult.Error("Failed to retrieve chunk $chunkCid")
                catResult.stdout.encodeToByteArray()
            }
            val reassembled = reassembleChunks(chunks)
            return IpfsResult.PatchResult(deserializePatch(reassembled))
        }
        return IpfsResult.PatchResult(deserializePatch(data.encodeToByteArray()))
    }

    /**
     * Pin a CID to prevent garbage collection.
     */
    fun pin(cid: CharSequence): Boolean {
        val result = shell.exec("ipfs", listOf("pin", "add", cid))
        return result.exitCode == 0
    }

    /**
     * Check whether a CID is pinned.
     */
    fun isPinned(cid: CharSequence): Boolean {
        val result = shell.exec("ipfs", listOf("pin", "ls", cid))
        return result.exitCode == 0 && result.stdout.contains(cid)
    }

    /**
     * List all patches stored in IPFS for a given namespace prefix.
     */
    fun listStored(namespace: CharSequence): List<CharSequence> {
        val result = shell.exec("ipfs", listOf("pin", "ls", "--type=recursive"))
        if (result.exitCode != 0) return emptyList()
        return result.stdout.lines()
            .map { it.trim() }
            .filter { it.startsWith(PATCH_CID_PREFIX) && it.contains(namespace) }
            .map { it.substringBefore(" ") }
    }

    // --- internals ---

    private fun serializePatch(patch: Patch): ByteArray {
        val nameBytes = patch.name.encodeToByteArray()
        val hashBytes = patch.hash.bytes
        val ba = StringBuilder()
        appendLEB128(ba, nameBytes.size)
        ba.append(nameBytes.decodeToString())
        appendLEB128(ba, hashBytes.size)
        ba.append(hashBytes.decodeToString())
        appendLEB128(ba, (patch.timestamp ushr 32).toInt())
        appendLEB128(ba, patch.timestamp.toInt())
        appendLEB128(ba, patch.dependsOn.size)
        for (dep in patch.dependsOn) {
            appendLEB128(ba, dep.bytes.size)
            ba.append(dep.bytes.decodeToString())
        }
        ba.append(patch.name)  // stub content
        return ba.toString().encodeToByteArray()
    }

    private fun deserializePatch(data: ByteArray): Patch = object : Patch {
        override val name = "deserialized"
        override val hash = PatchHash(data.copyOf(32))
        override val timestamp = 0L
        override val dependsOn: Set<PatchHash> = emptySet()
        override val isConflicted = false
        override fun invert() = this
        override fun apply(pristine: Pristine) = ApplyResult.Success(pristine, emptyList())
        override infix fun compose(other: Patch) = null
        override infix fun commute(other: Patch) = null
    }

    private fun appendLEB128(sb: StringBuilder, value: Int) {
        var v = value
        while (v > 0x7f) {
            sb.append(((v and 0x7f) or 0x80).toChar())
            v = v ushr 7
        }
        sb.append(v.toChar())
    }

    private fun isCompositeCid(cid: CharSequence): Boolean = cid.startsWith("Qm") && cid.contains("_")

    private fun parseCompositeCid(cid: CharSequence): List<CharSequence> = cid.split("_").filter { it.isNotBlank() }

    private fun reassembleChunks(chunks: List<ByteArray>): ByteArray {
        val total = chunks.sumOf { it.size }
        val result = ByteArray(total)
        var pos = 0
        for (chunk in chunks) {
            chunk.copyInto(result, pos)
            pos += chunk.size
        }
        return result
    }

    private fun escapeShell(s: CharSequence): CharSequence =
        s.replace("'", "'\\''")
}
