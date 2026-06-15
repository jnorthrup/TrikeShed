package borg.trikeshed.activejs

import kotlinx.coroutines.CoroutineContext

/** JVM implementation of PointcutEventProducer. */
actual class PointcutEventProducerImpl : kotlinx.coroutines.AsyncContextElement(), PointcutEventProducer {
    private val consumers = mutableListOf<PointcutEventConsumer>()
    
    override suspend fun open() {
        super.open()
        state = kotlinx.coroutines.context.ElementState.ACTIVE
    }
    
    override fun close() {
        consumers.clear()
        super.close()
    }
    
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
actual class PointcutEventConsumerImpl(private val producer: PointcutEventProducer) : kotlinx.coroutines.AsyncContextElement(), PointcutEventConsumer {
    override suspend fun open() {
        super.open()
        state = kotlinx.coroutines.context.ElementState.ACTIVE
    }
    
    override fun close() {
        producer.unregisterConsumer(this)
        super.close()
    }
    
    override fun onEvent(event: PointcutEvent) {
        // Override in subclasses
    }
}

actual fun CoroutineContext.getPointcutEventProducer(): PointcutEventProducer? = this[PointcutEventProducer.Key]
actual fun CoroutineContext.getPointcutEventConsumer(): PointcutEventConsumer? = this[PointcutEventConsumer.Key]