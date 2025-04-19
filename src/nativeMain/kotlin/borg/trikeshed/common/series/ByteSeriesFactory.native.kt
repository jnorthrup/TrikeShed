// ByteSeriesFactory.native.kt
actual object ByteSeriesFactory {
    actual fun fromBuffer(buffer: Any): ByteSeries = when (buffer) {
        is CPointer<ByteVar> -> CPointerByteSeries(buffer)
        else -> throw IllegalArgumentException("Unsupported buffer type: ${buffer::class}")
    }
    
    actual fun fromMutableBuffer(buffer: Any): MutableByteSeries = when (buffer) {
        is CPointer<ByteVar> -> MutableCPointerByteSeries(buffer)
        else -> throw IllegalArgumentException("Unsupported buffer type: ${buffer::class}")
    }
}
