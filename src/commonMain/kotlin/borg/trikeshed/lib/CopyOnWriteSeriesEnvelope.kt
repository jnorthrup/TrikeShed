package borg.trikeshed.lib

import kotlin.properties.Delegates


//factory method for CopyOnWriteSeries
inline val <reified T> Series<T>.cow: CopyOnWriteSeriesEnvelope<T> get() = CopyOnWriteSeriesEnvelope(CopyOnWriteLetterSeries(this.toArray()))

/**
 * CopyOnWriteSeries which creates a new copy of the backing Series on all mutation methods.
 *
 * this the envelope of a letter+envelope abstraction using CopyOnWriteSwappingSeries as the letter
 *
 * this contains a Long? immutable version attribute which is incremented during cloning, or stays null
 *
 */
class CopyOnWriteSeriesEnvelope<T>(
    letter1: CopyOnWriteLetterSeries<T>,
    var observer: ((Twin<Series<T>>) -> Unit)? = null,
    var versionObserver: ((Twin<Long?>) -> Unit)? = null,

) : MutableSeries<T> {

    var letter by Delegates.observable(letter1) { _, old, new ->
        observer?.invoke( old j new )
    }

    override val a: Int get() = letter.a
    override val b: (Int) -> T get() = letter.b
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
        letter = letter.append(item); return this
    }

    override fun minus(item: T): MutableSeries<T> {
        letter = letter.remove(item); return this
    }

    override fun plusAssign(item: T) {
        letter = letter.append(item)
    }

    override fun minusAssign(item: T) {
        letter = letter.remove(item)
    }

    //version from backing
    val version: Any get() = letter.version ?: letter.toString()
}

//factory method for passing in an observer to a CopyOnWriteSeries.letter

