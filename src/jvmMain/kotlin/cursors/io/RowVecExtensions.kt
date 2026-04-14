@file:Suppress("UNCHECKED_CAST")

package cursors.io

import cursors.context.Scalar
import cursors.context.Arity
import vec.macros.Vect02
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

fun RowVec.getInt(index: Int): Int? {
    val (value, metaFn) = this.second(index)
    val type = metaFn().getTypeMemento()
    return if (type == IOMemento.IoInt) value as? Int else null
}

fun RowVec.getString(index: Int): String? {
    val (value, metaFn) = this.second(index)
    val type = metaFn().getTypeMemento()
    return if (type == IOMemento.IoString) value as? String else null
}

fun RowVec.getFloat(index: Int): Float? {
    val (value, metaFn) = this.second(index)
    val type = metaFn().getTypeMemento()
    return if (type == IOMemento.IoFloat) value as? Float else null
}

fun RowVec.getDouble(index: Int): Double? {
    val (value, metaFn) = this.second(index)
    val type = metaFn().getTypeMemento()
    return if (type == IOMemento.IoDouble) value as? Double else null
}

fun <T : Any> RowVec.getTyped(index: Int, expectedType: KClass<T>, typeMemento: IOMemento): T? {
    val (value, metaFn) = this.second(index)
    val actualTypeMemento = metaFn().getTypeMemento()
    return if (actualTypeMemento == typeMemento) value as? T else null
}

fun CoroutineContext.getTypeMemento(): IOMemento? {
    val scalar = this[Arity.arityKey] as? Scalar
    return scalar?.first as? IOMemento
}

inline fun <reified T : Any> RowVec.asIterable(typeMemento: IOMemento): Iterable<T?> {
    return Iterable {
        object : Iterator<T?> {
            var index = 0
            override fun hasNext(): Boolean = index < first
            override fun next(): T? {
                return getTyped(index++, T::class, typeMemento)
            }
        }
    }
}

