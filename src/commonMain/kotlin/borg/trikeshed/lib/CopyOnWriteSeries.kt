package borg.trikeshed.lib

import borg.trikeshed.placeholder.lib.AppendableSeries


/**
 * this the envelope of a letter+envelope abstraction using CopyOnWriteSwappingSeries as the letter
 *
 */
class CopyOnWriteSeries<T >(var letter: CopyOnWriteSwappingSeries<T>) : MutableSeries<T> {
    override val a: Int
        get() = letter.a

    override val b: (Int) -> T
        get() = letter.b

    override fun set(index: Int, item: T) {
        letter = letter.set(index, item)
    }

    override fun add(item: T) {
        letter = letter.append(item)
    }

    override fun add(index: Int, item: T) {
        letter = letter.insert(index, item)
    }

    override fun removeAt(index: Int): T {
        val item = letter.b(index)
        letter = letter.removeAt(index)
        return item
    }

    override fun remove(item: T): Boolean {
        val i = letter.backing.indexOf(item)
        if (i != -1) {
            letter = letter.removeAt(i)
            return true
        }
        return false
    }

    override fun clear() {
        letter = letter.clear()
    }

    override fun append(item: T) {
        letter = letter.append(item)
    }

    override fun plus(item: T): MutableSeries<T> {
        letter = letter.append(item)
        return this
    }

    override fun minus(item: T): MutableSeries<T> {
        letter = letter.remove(item)
        return this
    }

    override fun plusAssign(item: T) {
        letter = letter.append(item)
    }

    override fun minusAssign(item: T) {
        letter = letter.remove(item)
    }


}