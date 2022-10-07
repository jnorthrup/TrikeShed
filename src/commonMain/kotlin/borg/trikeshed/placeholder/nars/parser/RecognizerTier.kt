package borg.trikeshed.placeholder.nars.parser

class RecognizerTier(vararg recognize: Byte) {
    val recognize: ULongArray by lazy {
        uLongArray(recognize)
    }

    //hashcode method  on recognize
    override fun hashCode(): Int {
        return recognize.contentHashCode() //is this on the contents ??
    }

    private fun uLongArray(recognize: ByteArray): ULongArray {
        val result = ULongArray(4)
        recognize.distinct().forEach { result[it / 64] = result[it / 64] or (1UL shl (it % 64)) }
        return result
    }

    companion object {
        /**singleton collection*/
        val bySource = linkedMapOf<String, RecognizerTier>() //   ByteArray(*) to Recognizer

        /** get or create an idempotent Recognizer from the ordered distinct list of bytes to recognize */
        fun byDistinct(vararg recognize: Byte): RecognizerTier {
            val sorted = recognize.distinct().sorted().toByteArray()
            val k: String = sorted.contentToString()
            //getoradd
            return bySource.getOrPut(k) { RecognizerTier(*sorted) }
        }

        /** get or create an idempotent Recognizer from the ordered distinct list of bytes to recognize */
        fun byString(recognize: String): RecognizerTier {
            val sorted = recognize.encodeToByteArray().distinct().sorted().toByteArray()
            return recognizer(sorted)
        }

        private fun recognizer(sorted: ByteArray): RecognizerTier {
            val k: String = sorted.contentToString()
            //getoradd
            return bySource.getOrPut(k) { RecognizerTier(*sorted) }
        }
    }
}