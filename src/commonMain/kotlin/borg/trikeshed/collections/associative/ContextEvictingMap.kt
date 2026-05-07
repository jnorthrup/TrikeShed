package borg.trikeshed.collections.associative

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import kotlinx.coroutines.Job

data class ContextEvictingNode<K : Any, V : Any>(
    val key: K,
    val owner: Job,
    val value: V,
) {
    val isLive: Boolean get() = owner.isActive
}

class ContextEvictingMap<K : Any, V : Any>(
    val mapOwner: Job,
) {
    private val backing = LinkedHashMap<K, ContextEvictingNode<K, V>>()

    init {
        mapOwner.invokeOnCompletion {
            backing.clear()
        }
    }

    fun bind(key: K, owner: Job = mapOwner, value: V) {
        if (!mapOwner.isActive || !owner.isActive) {
            backing.remove(key)
            return
        }

        val node = ContextEvictingNode(key, owner, value)
        backing[key] = node
        owner.invokeOnCompletion {
            val current = backing[key]
            if (current === node) {
                backing.remove(key)
            }
        }
    }

    operator fun get(key: K): V? {
        if (!mapOwner.isActive) {
            backing.clear()
            return null
        }

        val node = backing[key] ?: return null
        return if (node.isLive) {
            node.value
        } else {
            backing.remove(key)
            null
        }
    }

    fun containsKey(key: K): Boolean = get(key) != null

    fun evict(key: K): V? = backing.remove(key)?.value

    fun evictAll() {
        backing.clear()
    }

    fun reap(): Int {
        if (!mapOwner.isActive) {
            val removed = backing.size
            backing.clear()
            return removed
        }

        val dead = backing.filterValues { !it.isLive }.keys.toList()
        dead.forEach(backing::remove)
        return dead.size
    }

    fun size(): Int {
        reap()
        return backing.size
    }

    fun entries(): Series<Join<K, V>> {
        reap()
        val live = backing.values.toList()
        return live.size j { index -> live[index].key j live[index].value }
    }

    fun keys(): Series<K> {
        val liveEntries = entries()
        return liveEntries.a j { index -> liveEntries.b(index).a }
    }

    fun values(): Series<V> {
        val liveEntries = entries()
        return liveEntries.a j { index -> liveEntries.b(index).b }
    }
}
