package borg.literbike.concurrency

import kotlinx.coroutines.flow.*

/**
 * Simplified Flow module
 * Flow-based reactive streams with backpressure
 */

/**
 * Flow - a cold asynchronous stream of values
 */
class Flow<T : Any> private constructor(
    private val flow: kotlinx.coroutines.flow.Flow<T>
) {
    companion object {
        /**
         * Create a flow from a list
         */
        fun <T : Any> fromList(values: List<T>): Flow<T> {
            return Flow(flow { values.forEach { emit(it) } })
        }

        /**
         * Create a flow from a single value
         */
        fun <T : Any> just(value: T): Flow<T> {
            return Flow(flow { emit(value) })
        }

        /**
         * Create an empty flow
         */
        fun <T : Any> empty(): Flow<T> {
            return Flow(emptyFlow())
        }

        /**
         * Create a flow from a suspend function
         */
        fun <T : Any> fromSuspend(block: suspend () -> List<T>): Flow<T> {
            return Flow(flow {
                block().forEach { emit(it) }
            })
        }
    }

    /**
     * Collect values into a List
     */
    suspend fun toList(): List<T> = flow.toList()

    /**
     * Map operator - transform each element
     */
    fun <U : Any> map(transform: suspend (T) -> U): Flow<U> {
        return Flow(flow.map { transform(it) })
    }

    /**
     * Filter operator - keep only elements that match predicate
     */
    fun filter(predicate: suspend (T) -> Boolean): Flow<T> {
        return Flow(flow.transform { if (predicate(it)) emit(it) })
    }

    /**
     * Take operator - take first n elements
     */
    fun take(n: Int): Flow<T> {
        return Flow(flow.take(n))
    }

    /**
     * Drop operator - skip first n elements
     */
    fun drop(n: Int): Flow<T> {
        return Flow(flow.drop(n))
    }

    /**
     * Collect the flow
     */
    suspend fun collect(action: suspend (T) -> Unit) {
        flow.collect { action(it) }
    }

    /**
     * Get the underlying kotlinx Flow
     */
    fun asKotlinFlow(): kotlinx.coroutines.flow.Flow<T> = flow
}

/**
 * Flow builder for complex flow construction
 */
class FlowBuilder<T : Any> {
    private val values: MutableList<T> = mutableListOf()

    companion object {
        fun <T : Any> new() = FlowBuilder<T>()
    }

    fun add(value: T): FlowBuilder<T> {
        values.add(value)
        return this
    }

    fun build(): Flow<T> {
        return Flow.fromList(values.toList())
    }
}

/**
 * Flow operator trait for extensibility
 */
interface FlowOperator<T : Any> {
    fun apply(flow: Flow<T>): Flow<T>
}
