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
        capture: Join<CharSequence, Join<CharSequence, Join<Long, Join<CharSequence?, Join<CharSequence?, Join<CharSequence?, Join<CharSequence?, Map<CharSequence, CharSequence>?>>>>>>>
    ): Map<CharSequence, CharSequence>? {
        return capture.b.b.b.b.b.b.b
    }

    // ── Approach 2: Typed tuple (1 field read) ──


     inline class ObjStoreDirect(
        val bucket: CharSequence,
        val key: CharSequence,
        val byteSize: Long,
        val contentType: CharSequence?,
        val etag: CharSequence?,
        val lastModified: CharSequence?,
        val versionId: CharSequence?,
        val metadata: Map<CharSequence, CharSequence>?
    )

    fun metadataFromTuple(t: ObjStoreDirect): Map<CharSequence, CharSequence>? {
        return t.metadata
    }
}
