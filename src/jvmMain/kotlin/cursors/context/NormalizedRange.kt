package cursors.context

import vec.macros.Tw1n
import kotlin.coroutines.CoroutineContext

class NormalizedRange<T>(val range: Tw1n<T>) : CoroutineContext.Element {
    companion object {
        val normalizedRangeKey: CoroutineContext.Key<NormalizedRange<*>> = object : CoroutineContext.Key<NormalizedRange<*>> {}
    }

    override val key: CoroutineContext.Key<*>
        get() = normalizedRangeKey
}

