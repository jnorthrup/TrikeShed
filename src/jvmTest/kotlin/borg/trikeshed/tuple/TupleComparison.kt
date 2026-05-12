package borg.trikeshed.tuple

import borg.trikeshed.lib.Join

/**
 * Direct comparison of Join chains vs typed tuples.
 * No inlines on the Join half-accessors — plain delegation.
 */
object TupleComparison {

    // ── Approach 1: Join chain (7 levels deep) ──

    /** capture.b.b.b.b.b.b.b — seven field reads */
    fun metadataFromJoin(
        capture: Join<String, Join<String, Join<Long, Join<String?, Join<String?, Join<String?, Join<String?, Map<String, String>?>>>>>>>
    ): Map<String, String>? {
        return capture.b.b.b.b.b.b.b
    }

    // ── Approach 2: Typed tuple (1 field read) ──

    @JvmInline
    value class ObjStoreDirect(
        val bucket: String,
        val key: String,
        val byteSize: Long,
        val contentType: String?,
        val etag: String?,
        val lastModified: String?,
        val versionId: String?,
        val metadata: Map<String, String>?
    )

    fun metadataFromTuple(t: ObjStoreDirect): Map<String, String>? {
        return t.metadata
    }
}
