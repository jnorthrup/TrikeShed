package borg.trikeshed.collections

import borg.trikeshed.lib.Series

/**
 * Marks a collection that can provide a stable, point-in-time [Series] snapshot
 * of its elements. The snapshot should be immune to subsequent mutations of
 * the parent collection.
 */
interface Snapshotable<T> {
    fun snapshot(): Series<T>
}
