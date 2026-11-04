package borg.trikeshed.job

/**
 * Optional bounded-memory verification. False means absent or a different declared length;
 * corruption is an error. This authenticates the bytes read, not future external mutations.
 */
interface CasVerifier {
    suspend fun verify(cid: ContentId, length: Long): Boolean
}
