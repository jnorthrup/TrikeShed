package borg.trikeshed.activejs

import kotlin.coroutines.CoroutineContext

/** JVM implementation of PointcutEventProducer. */
class JvmPointcutEventProducer : PointcutEventProducer {
    private val consumers = mutableListOf<PointcutEventConsumer>()
    
    override val key: CoroutineContext.Key<PointcutEventProducer> get() = Key
    
    companion object Key : CoroutineContext.Key<PointcutEventProducer>
    
    override fun emit(event: PointcutEvent) {
        for (consumer in consumers) {
            consumer.onEvent(event)
        }
    }
    
    override fun registerConsumer(consumer: PointcutEventConsumer) {
        if (consumer !in consumers) consumers += consumer
    }
    
    override fun unregisterConsumer(consumer: PointcutEventConsumer) {
        consumers -= consumer
    }
}

/** JVM implementation of PointcutEventConsumer. */
class JvmPointcutEventConsumer(private val producer: PointcutEventProducer) : PointcutEventConsumer {
    override val key: CoroutineContext.Key<PointcutEventConsumer> get() = Key
    
    companion object Key : CoroutineContext.Key<PointcutEventConsumer>
    
    override fun onEvent(event: PointcutEvent) {
        // Override in subclasses
    }
}

/** JVM implementation of PointcutEventFactory. */
actual object PointcutEventFactory {
    private var producer: JvmPointcutEventProducer? = null
    private var consumer: JvmPointcutEventConsumer? = null
    
    actual fun createProducer(): PointcutEventProducer {
        return JvmPointcutEventProducer()
    }
    
    actual fun createConsumer(producer: PointcutEventProducer): PointcutEventConsumer {
        return JvmPointcutEventConsumer(producer)
    }
    
    actual fun getProducer(): PointcutEventProducer? {
        return producer
    }
    
    actual fun getConsumer(): PointcutEventConsumer? {
        return consumer
    }
    
    fun setProducer(p: JvmPointcutEventProducer) {
        producer = p
    }
    
    fun setConsumer(c: JvmPointcutEventConsumer) {
        consumer = c
    }
}