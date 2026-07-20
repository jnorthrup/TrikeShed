cat << 'MERGE' > /tmp/merge_cas.diff
<<<<<<< SEARCH
package borg.trikeshed.job

import borg.trikeshed.collections.associative.LinearHashMap as CasHashMap

/**
 * CasStore — content-addressable store.
 * In-memory implementation: SHA-256 keyed blob map with digest verification on read.
 */
open class CasStore protected constructor(
    private val blobs: CasHashMap<ContentId, ByteArray> = CasHashMap(),
) {
    open fun put(bytes: ByteArray): ContentId {
=======
package borg.trikeshed.job

/**
 * CasStore — content-addressable store.
 * In-memory implementation: SHA-256 keyed blob map with digest verification on read.
 */
open class CasStore protected constructor(
    private val blobs: MutableMap<ContentId, ByteArray> = mutableMapOf(),
) : borg.trikeshed.cas.CasStore {
    override fun put(bytes: ByteArray): ContentId {
>>>>>>> REPLACE
MERGE
patch -u src/commonMain/kotlin/borg/trikeshed/job/CasStore.kt -i /tmp/merge_cas.diff
