package borg.trikeshed.placeholder.lib

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size

interface AppendableSeries<T> : Series<T> {
    fun append(item: T)
}

//series val appendable
inline val <reified T> Series<T>.appendable: AppendableSeries<T>

    get() = this.let { dad ->
        if (this is AppendableSeries<*>) this as AppendableSeries<T>
        else run {
            val backing: MutableList<T> = ArrayList(size)
            object : AppendableSeries<T> {
                operator fun get(index: Int): T = backing[index]
                override fun append(item: T) {
                    backing.add(item)
                }

                override val a: Int by backing::size
                override val b: (Int) -> T = backing::get
            }
        }
    } as AppendableSeries<T>

operator fun <T> AppendableSeries<T>.plusAssign(item: T) = append(item)
val  Series<Char>.appendable: AppendableSeries<Char>
    get() = this.let { dad ->
        if (this is AppendableSeries<Char>) this
        else run {
            //offload this to StringBuilder
            object : AppendableSeries<Char> {
                val backing = StringBuilder(object : CharSequence {
                    override val length by ::size
                    override fun get(index: Int): Char = this[index]
                    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
                        StringBuilder().apply { for (i in startIndex until endIndex) append(dad[i]) }.toString()
                })
                operator fun get(index: Int): Char = backing[index]
                override fun append(item: Char) {
                    backing.append(item)
                }

                override val a: Int get() = backing.length
                override val b: (Int) -> Char get() = backing::get
            }
        }
    }


operator fun AppendableSeries<Char>.plusAssign(item: Char) = append(item)
operator fun AppendableSeries<Char>.plusAssign(item: String) = item.forEach { append(it) }

