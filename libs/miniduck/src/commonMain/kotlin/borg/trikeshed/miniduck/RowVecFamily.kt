package borg.trikeshed.miniduck

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.j

// Extension function for .mutable() call pattern
fun RowVec.mutable(): MutableList<RowVec> = mutableListOf()

// RowVec family - unified contract, bifurcation only at factory time
// These are typealiases, NOT new types - preserving the pure Join algebra
// The contracts are defined in borg.trikeshed.cursor package

/** MiniRowVec - unified contract, exposed from cursor package */
typealias MiniRowVec = borg.trikeshed.cursor.RowVec

/** MiniCursor - unified contract, exposed from cursor package */
typealias MiniCursor = borg.trikeshed.cursor.Cursor

/** Cursor - unified contract, exposed from cursor package */
typealias Cursor = borg.trikeshed.cursor.Cursor

/** BlockRowVec - RowVec for block storage (e.g., CouchDB row blocks) */
typealias BlockRowVec = borg.trikeshed.cursor.RowVec

/** DocRowVec - RowVec for document rows */
typealias DocRowVec = borg.trikeshed.cursor.RowVec

/** ViewRowVec - RowVec for view query results */
typealias ViewRowVec = borg.trikeshed.cursor.RowVec

/** JsonRowVec - RowVec for JSON documents */
typealias JsonRowVec = borg.trikeshed.cursor.RowVec

/** YamlRowVec - RowVec for YAML documents */
typealias YamlRowVec = borg.trikeshed.cursor.RowVec

/** BlobRowVec - RowVec for binary/blob data */
typealias BlobRowVec = borg.trikeshed.cursor.RowVec

// Factory functions - these create the bifurcation point at factory time

/**
 * Create a mutable builder for building up rows.
 * Returns a MutableList that can be appended to, then sealed.
 */
fun mutable(): MutableList<RowVec> = mutableListOf()

/**
 * Create a DocRowVec from keys and cells lists.
 * This is the factory function with named parameters.
 */
fun DocRowVec(keys: List<String>, cells: List<Any?>): RowVec {
    require(keys.size == cells.size) { "Keys and cells must have same size" }
    // For now, return empty RowVec - the actual construction should use cursor algebra
    return 0 j { _: Int -> throw NotImplementedError("Use cursor package") }
}

/**
 * Create a ViewRowVec from view query result components.
 * This is the factory function with named parameters.
 */
fun ViewRowVec(
    id: String,
    key: Any?,
    value: Any?,
    docLoader: (() -> RowVec)?,
): RowVec {
    // For now, return empty RowVec - the actual construction should use cursor algebra
    return 0 j { _: Int -> throw NotImplementedError("Use cursor package") }
}

/** Stub exception */
class NotImplementedError(msg: String) : Error(msg)