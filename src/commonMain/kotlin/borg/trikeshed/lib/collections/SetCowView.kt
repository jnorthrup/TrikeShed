package borg.trikeshed.lib.collections

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

//same for Set:
class SetCowView<T>(var list: Set<T>) : Set<T>, AbstractMutableSet<T>() {
    //keep our inital list until a mutable operation, then replace with .toMutableSet
    var once: Mutex? = Mutex()

    var guardFunction:(()->Unit)? = {
        runBlocking {
            once?.withLock { //thundering herds may all arrive here at once, but only one will get to copy the list
                if (list !is MutableSet<T>) {
                    list = list.toMutableSet()
                }
                once = null
                guardFunction = null
            }
        }
    }

    override fun add(element: T): Boolean {
        guardFunction?.invoke()
        return (list as MutableSet<T>).add(element)
    }

    override val size: Int
        get() = list.size

    override fun contains(element: T): Boolean {
        return list.contains(element)
    }

    override fun iterator(): MutableIterator<T> {
        return list.iterator() as MutableIterator<T>
    }

    override fun toString(): String {
        return "SetCowView(list=$list)"
    }
}