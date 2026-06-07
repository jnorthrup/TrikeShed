package borg.trikeshed.chronicle

import borg.trikeshed.context.FanoutDispatcherElement.DeliveryOutcome
import borg.trikeshed.context.ElementState
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view
import borg.trikeshed.splat.Splat
import borg.trikeshed.splat.toChronology

class CircularQueue<T>(private val capacity: Int) {
    private var head = 0
    private var tail = 0
    private var count = 0
    private val buffer = arrayOfNulls<Any?>(capacity)

    fun enqueue(item: T) {
        if (count == capacity) {
            head = (head + 1) % capacity
        } else {
            count++
        }
        buffer[tail] = item
        tail = (tail + 1) % capacity
    }

    val size: Int get() = count

    @Suppress("UNCHECKED_CAST")
    operator fun get(index: Int): T {
        if (index >= count) throw IndexOutOfBoundsException("Index $index out of bounds for size $count")
        return buffer[(head + index) % capacity] as T
    }
}

object Chronicle {
    private val buffer = CircularQueue<ChronicleEvent>(capacity = 1_000_000)

    fun emit(event: ChronicleEvent) {
        buffer.enqueue(event)
    }

    fun flushToSeries(): Series<String> = buffer.size.j { i -> buffer[i].toJson() }
}

sealed class ChronicleEvent {
    abstract fun toJson(): String
}

data class TransitionSplat(
    val elementKey: String,
    val from: ElementState,
    val splat: Splat<ElementState>?,
    val actual: ElementState,
    val composition: Join<String, Series<String>>
) : ChronicleEvent() {
    override fun toJson(): String = buildString {
        append("[TRANSITION] $elementKey: $from → $actual\n")
        splat?.let { append("[SPLAT-STATE] ${it.toChronology()}\n") }
        append("[COMPOSITION] ${composition.a}: ${composition.b.view.joinToString()}\n")
    }
}

data class FanoutSplat(
    val dispatcherId: Int,
    val eventType: String,
    val splats: Series<Join<String, Splat<DeliveryOutcome>>>,
    val actuals: Series<Join<String, DeliveryOutcome>>,
    val subscriberCount: Int
) : ChronicleEvent() {
    override fun toJson(): String = buildString {
        append("[FANOUT] dispatcher=$dispatcherId event=$eventType subscribers=$subscriberCount\n")
        append("[SPLAT-FANOUT] {\n")
        repeat(splats.size) { i ->
            val pair = splats.b(i)
            val sub = pair.a
            val splat = pair.b
            append("  \"$sub\": ${splat.toChronology()},\n")
        }
        append("}\n")
        append("[ACTUAL-FANOUT]\n")
        repeat(actuals.size) { i ->
            val pair = actuals.b(i)
            val sub = pair.a
            val outcome = pair.b
            append("  ├─ $sub: $outcome\n")
        }
    }
}
