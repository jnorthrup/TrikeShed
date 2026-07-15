package borg.trikeshed.job

/**
 * Very narrow interface for verifying existence of CAS blobs.
 */
interface CasStore {
    /**
     * Checks if a referenced Content Addressed Storage blob exists.
     * @param cid Content Identifier
     * @return true if it exists, false otherwise.
     */
    suspend fun contains(cid: String): Boolean
}
