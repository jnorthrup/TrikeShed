package borg.trikeshed.htx.client.ipfs

import borg.trikeshed.job.BtrfsCasStore
import borg.trikeshed.job.ContentId
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * Btrfs-backed BlockStore — content-addressed blocks persisted through the
 * userspace-btrfs CAS store ([BtrfsCasStore]): temp-write + reflink (CoW),
 * identical content dedupes to a single physical extent.
 *
 * The CID domain here is raw-SHA-256 hex (64 chars); [ContentId] prefixes the
 * same digest with "sha256:" — bridging is byte-exact in both directions.
 */
class BtrfsBlockStore(
    rootDir: File = File(System.getProperty("user.home"), ".local/forge/btrfs-cas"),
) : BlockStore {
    private val cas = BtrfsCasStore(rootDir)

    override suspend fun put(cid: CID, data: ByteArray) {
        val stored = cas.put(data)
        // content-addressed: stored cid is derived from bytes. A caller cid that
        // disagrees with the bytes is a contract violation — fail loudly.
        check(stored.hex == cid.hex()) {
            "BtrfsBlockStore.put: cid ${cid.hex()} does not match content digest ${stored.hex}"
        }
    }

    override suspend fun get(cid: CID): ByteArray? {
        val bytes = runBlocking { cas.get(ContentId("sha256:${cid.hex()}")) } ?: return null
        // verify on read — a torn CoW write must never escape
        return if (ContentId.of(bytes).hex == cid.hex()) bytes else null
    }

    override suspend fun has(cid: CID): Boolean = get(cid) != null
}
