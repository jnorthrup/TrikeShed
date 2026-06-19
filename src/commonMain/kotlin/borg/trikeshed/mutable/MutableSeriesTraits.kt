package borg.trikeshed.mutable

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Twin

// ──────────────────────────────────────────────────────────────────────────
//  Capability Traits — compose what you need
// ──────────────────────────────────────────────────────────────────────────

/**
 * Append — grow by pushing to tail (amortized O(1)).
 * Use this trait when you only need append capability.
 */
interface Appendable<T> {
    fun append(item: T): Unit
    fun insert(index: Int, item: T): Unit
}

/**
 * RandomAccess — index-based get/set in O(1).
 */
interface RandomAccess<T> {
    operator fun get(index: Int): T
    operator fun set(index: Int, item: T): Unit
    val size: Int
}

/**
 * Removable — delete elements (not all mutable series support removal).
 */
interface Removable<T> {
    fun removeAt(index: Int): T
    fun remove(item: T): Boolean
    fun clear(): Unit
}

/**
 * Observable — subscribe to mutations via Twin<Series>.
 */
interface Observable<T> {
    fun subscribe(observer: (Twin<Series<T>>) -> Unit): () -> Unit
    fun version(): Long
}

/**
 * Freezable — seal mutations, enable structural sharing.
 */
interface Freezable<T> {
    fun freeze(): Series<T>
    fun cowSnapshot(): MutableSeries<T>
    val isFrozen: Boolean
}

// ──────────────────────────────────────────────────────────────────────────
//  Drain Pipeline Traits — staged data processing
// ──────────────────────────────────────────────────────────────────────────

/**
 * DrainReceiver — can receive drained elements from an upstream stage.
 */
interface DrainReceiver<T> {
    fun receive(item: T): Unit
    fun receiveBatch(items: Series<T>): Unit
    val receivedSize: Int
}

/**
 * DrainSender — can drain to a downstream receiver.
 */
interface DrainSender<T> {
    var drainTo: DrainReceiver<T>?
    fun drain(): Unit
    val pendingSize: Int
}

/**
 * DrainStage — both receiver and sender (for chaining).
 */
interface DrainStage<T> : DrainReceiver<T>, DrainSender<T>