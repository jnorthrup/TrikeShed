package borg.trikeshed.couch.stream

/**
 * Simple Change event + ChangeEmitter used by RED tests.
 * Minimal, synchronous fan-out; preserves registration order.
 */
sealed class Change<out T> {
    enum class Kind { INSERT, REMOVE, SEAL }
    abstract val kind: Kind
    abstract val key: T?

    data class Insert<T>(val k: T) : Change<T>() {
        override val kind: Kind = Kind.INSERT
        override val key: T = k
    }

    data class Remove<T>(val k: T) : Change<T>() {
        override val kind: Kind = Kind.REMOVE
        override val key: T = k
    }

    object Seal : Change<Nothing>() {
        override val kind: Kind = Kind.SEAL
        override val key: Nothing? = null
    }
}

/**
 * Very small synchronous emitter for test use.
 */
class ChangeEmitter<T> {
   val listeners: MutableMap<Int, (Change<T>) -> Unit> = linkedMapOf()
   var nextToken = 0
   var sealed: Boolean = false

    /** Register a listener. Returns a token which can be used to unregister. */
    fun register(cb: (Change<T>) -> Unit): Int {
        val id = nextToken++
        listeners[id] = cb
        return id
    }

    fun unregister(token: Int) {
        listeners.remove(token)
    }

    fun emit(change: Change<T>) {
        // if already sealed, ignore subsequent events
        if (sealed) return
        // Mark sealed if this is the seal event so later emits are ignored
        if (change is Change.Seal) sealed = true
        // iterate over snapshot to avoid concurrent-modification surprises
        val snapshot = listeners.values.toList()
        for (cb in snapshot) cb(change)
    }
}
