@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.collections.associative

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**
 * Item: the shared metamodel for JSON / YAML / CBOR.
 *
 * Every branch is either Join or Series -- no new abstract primitives.
 *   Map  = Series<Join<CharSequence, Item>>   (lazy key-value pairs)
 *   Arr  = Series<Item>                  (lazy sequence)
 *   Scalars: Str, Bin, Num, Flt, Bool, Nil
 *   Tag: CBOR tag (ignored by JSON/YAML)
 */
sealed interface Item {

    data class Map(val entries: Series<Join<CharSequence, Item>>) : Item {
        val size: Int get() = entries.a
        operator fun get(key: CharSequence): Item? {
            for (i in 0 until entries.a) {
                val e = entries.b(i)
                if (e.a == key) return e.b
            }
            return null
        }
        fun keys(): List<CharSequence> = (0 until entries.a).map { entries.b(it).a }
        fun values(): List<Item> = (0 until entries.a).map { entries.b(it).b }
        fun containsKey(key: CharSequence): Boolean = get(key) != null
    }

    data class Arr(val items: Series<Item>) : Item {
        val size: Int get() = items.a
        operator fun get(index: Int): Item = items.b(index)
    }

    data class Str(val value: CharSequence) : Item
    data class Bin(val value: ByteArray) : Item {
        override fun equals(other: Any?): Boolean = other is Bin && value.contentEquals(other.value)
        override fun hashCode(): Int = value.contentHashCode()
    }
    data class Num(val value: Long) : Item
    data class Flt(val value: Double) : Item
    data class Bool(val value: Boolean) : Item
    object Nil : Item
    data class Tag(val tag: UInt, val item: Item) : Item
}

fun itemMapOf(vararg pairs: Pair<CharSequence, Item>): Item.Map =
    Item.Map(pairs.size j { pairs[it].first j pairs[it].second })

fun itemMapOf(map: Map<CharSequence, Item>): Item.Map {
    val entries = map.entries.toList()
    return Item.Map(entries.size j { entries[it].key j entries[it].value })
}

fun itemArrayOf(vararg items: Item): Item.Arr = Item.Arr(items.size j { items[it] })
fun itemArrayOf(items: List<Item>): Item.Arr = Item.Arr(items.size j { items[it] })

fun Any?.toItem(): Item = when (this) {
    null -> Item.Nil
    is Item -> this
    is CharSequence -> Item.Str(this)
    is Long -> Item.Num(this)
    is Int -> Item.Num(this.toLong())
    is Short -> Item.Num(this.toLong())
    is Byte -> Item.Num(this.toLong())
    is Double -> Item.Flt(this)
    is Float -> Item.Flt(this.toDouble())
    is Boolean -> Item.Bool(this)
    is ByteArray -> Item.Bin(this)
    is Map<*, *> -> {
        val entries = entries.toList()
        Item.Map(entries.size j {
            val e = entries[it]
            (e.key?.toString() ?: "") j (e.value.toItem())
        })
    }
    is List<*> -> Item.Arr(size j { this[it].toItem() })
    is Array<*> -> Item.Arr(size j { this[it].toItem() })
    else -> Item.Str(toString())
}

fun Item.toAny(): Any? = when (this) {
    is Item.Nil -> null
    is Item.Str -> value
    is Item.Num -> value
    is Item.Flt -> value
    is Item.Bool -> value
    is Item.Bin -> value
    is Item.Map -> {
        val m = LinkedHashMap<CharSequence, Any?>(size)
        for (i in 0 until entries.a) {
            m[entries.b(i).a] = entries.b(i).b.toAny()
        }
        m
    }
    is Item.Arr -> (0 until items.a).map { items.b(it).toAny() }
    is Item.Tag -> item.toAny()
}

val Item?.strValue: CharSequence? get() = (this as? Item.Str)?.value
val Item?.longValue: Long? get() = (this as? Item.Num)?.value
val Item?.boolValue: Boolean? get() = (this as? Item.Bool)?.value
