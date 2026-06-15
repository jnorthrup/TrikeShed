package borg.trikeshed.activejs

import kotlinx.coroutines.CoroutineContext

/** Linux stub implementation of PointcutEventProducer. */
actual class PointcutEventProducerImpl : kotlinx.coroutines.AsyncContextElement(), PointcutEventProducer {
    override suspend fun open() { super.open() }
    override fun close() { super.close() }
    override fun emit(event: PointcutEvent) {}
    override fun registerConsumer(consumer: PointcutEventConsumer) {}
    override fun unregisterConsumer(consumer: PointcutEventConsumer) {}
}

/** Linux stub implementation of PointcutEventConsumer. */
actual class PointcutEventConsumerImpl(private val producer: PointcutEventProducer) : kotlinx.coroutines.AsyncContextElement(), PointcutEventConsumer {
    override suspend fun open() { super.open() }
    override fun close() { super.close() }
    override fun onEvent(event: PointcutEvent) {}
}

actual fun CoroutineContext.getPointcutEventProducer(): PointcutEventProducer? = null
actual fun CoroutineContext.getPointcutEventConsumer(): PointcutEventConsumer? = null