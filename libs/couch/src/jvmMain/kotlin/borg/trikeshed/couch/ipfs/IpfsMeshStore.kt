package borg.trikeshed.couch.ipfs

import borg.trikeshed.couch.ConfixDocStore
import borg.trikeshed.couch.ConfixDocStoreEntry
import borg.trikeshed.couch.RevPolicy
import borg.trikeshed.parse.confix.ConfixDoc
import java.security.MessageDigest
import java.util.Base64

/**
 * An IPFS-style Mesh Store that acts as a wrapper around ConfixDocStore.
 *
 * In a true IPFS mesh, documents are content-addressed by their CID (Content Identifier),
 * typically a SHA-256 hash of their contents. This store mimics that behavior:
 * inserting a document computes its CID, and the document is stored using the CID as its ID.
 */
class IpfsMeshStore(
    val backingStore: ConfixDocStore = ConfixDocStore(RevPolicy.UUID)
) {

    /**
     * Compute a mock IPFS CID (SHA-256 hash) for the given ConfixDoc.
     * In a real implementation, this would use a proper Multihash format (e.g. base58btc).
     * For simulation, we use Base64 URL-safe encoding of the SHA-256 hash of the doc's string representation.
     */
    fun computeCid(doc: ConfixDoc): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // A naive representation for hashing. In production, this must be a deterministic JSON or CBOR encoding.
        val hashBytes = digest.digest(doc.toString().toByteArray(Charsets.UTF_8))
        return "Qm" + Base64.getUrlEncoder().withoutPadding().encodeToString(hashBytes) // Prefix 'Qm' to simulate CIDv0
    }

    /**
     * Put a document into the IPFS mesh store.
     * It computes the CID and uses that as the _id in the backing store.
     *
     * @return The resulting store entry, or null if a conflict occurred (though CIDs are immutable, so conflict is unlikely for new content).
     */
    fun putContent(doc: ConfixDoc): ConfixDocStoreEntry? {
        val cid = computeCid(doc)

        // If it already exists, return the existing entry (content addressing guarantees it's the same content)
        val existing = backingStore[cid]
        if (existing != null) {
            return existing
        }

        return backingStore.put(cid, doc)
    }

    /**
     * Retrieve a document by its CID.
     */
    fun getByCid(cid: String): ConfixDocStoreEntry? {
        return backingStore[cid]
    }

    /**
     * Pin a document to the local node (simulated by ensuring it exists in the backing store).
     * This is useful for mesh replication scenarios.
     */
    fun pin(cid: String, doc: ConfixDoc): Boolean {
        val computedCid = computeCid(doc)
        if (computedCid != cid) {
            return false // Invalid CID for this content
        }
        putContent(doc)
        return true
    }
}
