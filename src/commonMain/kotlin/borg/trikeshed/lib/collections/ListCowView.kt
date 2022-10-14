package borg.trikeshed.lib.collections

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** a mutable listView of a List which performs a copy to MutableList on first mutation.  not threadsafe or concurrent. */
class ListCowView<T>(private var list: List<T> = emptyList()) : List<T>, AbstractMutableList<T>() {
    //keep our inital list until a mutable operation, then replace with .toMutableList
   private var once: Mutex? = Mutex()

  private  var guardFunction:(()->Unit)? = {
        runBlocking {
            once?.withLock { //thundering herds may all arrive here at once, but only one will get to copy the list
                if (list !is MutableList<T>) {
                    list = list.toMutableList()
                }
                once = null
                guardFunction = null
            }
        }
    }

    override fun add(index: Int, element: T) {
        guardFunction?.invoke()
        (list as MutableList<T>).add(index, element)
    }

    override val size: Int
        get() = list.size

    override fun get(index: Int): T {
        return list[index]
    }

    override fun removeAt(index: Int): T {
        guardFunction?.invoke()
        return (list as MutableList<T>).removeAt(index)
    }

    override fun set(index: Int, element: T): T {
        guardFunction?.invoke()
        return (list as MutableList<T>).set(index, element)
    }

    override fun toString(): String {
        return "ListCowView(list=$list)"
    }
}