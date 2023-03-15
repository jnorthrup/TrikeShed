package borg.trikeshed.lib

import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size

interface AppendableSeries<T> : Series<T> { fun append(item: T) }

//series val appendable
inline val <reified T> Series<T>.appendable: AppendableSeries<T>
    get() = this.let {
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
    }


operator fun AppendableSeries<Char>.plusAssign(item: Char): Unit = append(item)
operator fun AppendableSeries<Char>.plusAssign(item: String): Unit = item.forEach { append(it) }

