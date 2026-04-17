package cursors.context

import kotlin.coroutines.CoroutineContext

sealed class Ordering : CoroutineContext.Element {
    override val key: CoroutineContext.Key<Ordering> get() = orderingKey

    companion object {
        val orderingKey: CoroutineContext.Key<Ordering> = object : CoroutineContext.Key<Ordering> {}
    }
}

class RowMajor : Ordering()
abstract class ColumnMajor : Ordering()
abstract class Hilbert : Ordering()
abstract class RTree : Ordering()

