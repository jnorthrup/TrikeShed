package borg.trikeshed.parse

interface DocumentBitmap {
    enum class LexerEvents(val predicate: (UByte) -> Boolean) {
        Unchanged({ false }),
        QuoteIncrement({ it.toUInt() == 0x22U }), // "
        EscapeIncrement({ it.toUInt() == 0x5cU }), // \
        UtfInitiatorOrContinuation({ it >= 0x80U }) // UTF-8 multi-byte
        ;
        
        companion object {
            val cache: Array<LexerEvents> = entries.drop(1).toTypedArray()
            fun test(byte: UByte): Int {
                return cache.firstOrNull { it.predicate(byte) }?.ordinal ?: Unchanged.ordinal
            }
        }
    }

    fun encode(input: UByteArray): UByteArray
    fun decode(input: Array<UByteArray>, inputSize: UInt = input.sumOf { it.size.toUInt() * 2U }): Array<UByteArray>
}
