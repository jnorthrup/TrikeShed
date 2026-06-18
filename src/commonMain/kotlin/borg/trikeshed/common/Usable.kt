package borg.trikeshed.common

/**
 * a horrible hack to enable use() macro in kotlin mpp for our file messes
 */
interface Usable {
    /**
     * contract - does no harm if called more than once
     */
    fun open()
    /**
     * contract - does no harm if called more than once
     */
    fun close()
}

/** this is not present in stdlib for kotlin mpp, we extend the functionality to return a value as needed.*/
fun <T : Usable, R> T.use(block: (T) -> R): R {
    open()
    try {
        return block(this)
    } finally {
        close()
    }
}
