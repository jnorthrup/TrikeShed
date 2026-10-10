package borg.trikeshed.loom

/**
 * cocaine-rats crates/loom-mesh/src/gcs.rs, the parts the wire objects carry. GcsConfig holds the two
 * fields a [Handoff] binds to; Rust's also carries the GCS client's credentials and timeout.
 */
class GcsConfig(val bucket: String, val prefix: String = "loom-settlement/v1")

/** A held, generation-pinned GCS object. */
class GcsReceipt(
    val bucket: String,
    val `object`: String,
    val generation: ULong,
    val size: ULong,
    val sha256: ByteArray,
) {
    fun validate() {
        if (!safe_bucket(bucket) || !safe_name(`object`, 256) || generation == 0uL ||
            generation > Long.MAX_VALUE.toULong() || size == 0uL || size > MAX_WIRE.toULong()
        ) error("invalid GCS receipt")
    }
}

fun safe_bucket(bucket: String): Boolean =
    bucket.length in 3..63 && bucket.all { it in 'a'..'z' || it in '0'..'9' || it == '-' } &&
        bucket.first() != '-' && bucket.last() != '-'

fun safe_name(name: String, cap: Int): Boolean =
    name.isNotEmpty() && name.length <= cap && name.split('/').all { part ->
        part.isNotEmpty() && part != "." && part != ".." &&
            part.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' || it == '.' }
    }
