package borg.trikeshed.lib.collections

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

//same for Set:
class SetCowView<T>(private var tSet: Set<T> = emptySet() ) : Set<T>, AbstractMutableSet<T>() {
    //keep our inital list until a mutable operation, then replace with .toMutableSet
    private var once: Mutex? = Mutex()

    private var guardFunction: (() -> Unit)? = {
        runBlocking {
            once?.withLock { //thundering herds may all arrive here at once, but only one will get to copy the list
                if (tSet !is MutableSet<T>) {
                    tSet = tSet.toMutableSet()
                }
                once = null
                guardFunction = null
            }
        }
    }

    override fun add(element: T): Boolean {
        guardFunction?.invoke()
        return (tSet as MutableSet<T>).add(element)
    }

    override val size: Int
        get() = tSet.size

    override fun contains(element: T): Boolean {
        return tSet.contains(element)
    }

    @Deprecated("undefined behavior")
    override fun iterator(): MutableIterator<T> {
        return tSet.iterator() as MutableIterator<T>
    }

    override fun toString(): String {
        return "SetCowView(list=$tSet)"
    }
}