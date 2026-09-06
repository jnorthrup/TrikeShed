package borg.trikeshed.cas

import borg.trikeshed.job.ContentId

/** SHA-256 objects fan out through four single-hex directories. */
object CasPaths {
    fun shard(cid: ContentId): String = cid.hex.let { hex ->
        "sha256/${hex[0]}/${hex[1]}/${hex[2]}/${hex[3]}"
    }

    fun blob(cid: ContentId): String = "${shard(cid)}/${cid.hex.substring(4)}"

    /** Read compatibility for objects written before the four-directory layout. */
    fun legacyBlob(cid: ContentId): String = cid.hex.let { hex ->
        "sha256/${hex.substring(0, 2)}/${hex.substring(2)}"
    }
}
