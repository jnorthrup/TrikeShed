package borg.trikeshed.activejs

/** WASM stub implementation of PointcutEventFactory. */
actual object PointcutEventFactory {
    actual fun createProducer(): PointcutEventProducer = NoOpPointcutEventProducer()
    
    actual fun createConsumer(producer: PointcutEventProducer): PointcutEventConsumer = NoOpPointcutEventConsumer(producer)
    
    actual fun getProducer(): PointcutEventProducer? = null
    
    actual fun getConsumer(): PointcutEventConsumer? = null
}