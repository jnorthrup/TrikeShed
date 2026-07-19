package borg.trikeshed.pointcut

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.CopyOnWriteArrayList

object PointcutReporter {
    private const val BUFFER_SIZE = 100_000
    private val buffer = AtomicReferenceArray<PointcutEvent>(BUFFER_SIZE)
    private val head = AtomicInteger(0)
    private val subscribers = CopyOnWriteArrayList<(PointcutEvent) -> Unit>()

    @Volatile
    var vetoCallback: ((PointcutEvent) -> Boolean)? = null

    @Volatile
    var blackboardCallback: ((PointcutEvent) -> Unit)? = null

    @JvmStatic
    fun report(vmId: String, coordinate: String, target: Any?, propertyName: String, newValue: Any?) {
        val vmFacet = VmFacet.values().find { it.id == vmId } ?: VmFacet.JVM
        val event = PointcutEvent(vmFacet, coordinate, target, propertyName, newValue)

        val veto = vetoCallback
        if (veto != null && !veto(event)) {
            throw SecurityException("Vetoed modification of $propertyName to $newValue")
        }

        val index = head.getAndIncrement() % BUFFER_SIZE
        buffer.set(index, event)

        blackboardCallback?.invoke(event)
        subscribers.forEach { it(event) }
    }

    fun subscribe(subscriber: (PointcutEvent) -> Unit): () -> Unit {
        subscribers.add(subscriber)
        return { subscribers.remove(subscriber) }
    }

    fun getEvents(): List<PointcutEvent> {
        val count = minOf(head.get(), BUFFER_SIZE)
        val result = mutableListOf<PointcutEvent>()
        for (i in 0 until count) {
            buffer.get(i)?.let { result.add(it) }
        }
        return result
    }

    fun clear() {
        head.set(0)
        for (i in 0 until BUFFER_SIZE) {
            buffer.set(i, null)
        }
    }
}
