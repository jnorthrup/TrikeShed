package borg.trikeshed.litebike

/** Limits shared by Documents framing, browser selection and the ingress listener. */
object DocumentBatch {
    const val FILES = 1000
    const val FILE_BYTES = 32 * 1024 * 1024
    const val TOTAL_BYTES = 128 * 1024 * 1024
    const val PATH_UNITS = 1024
    const val PATH_BYTES = PATH_UNITS * 3
    const val BODY_BYTES = FILE_BYTES + 12 + PATH_BYTES
    const val HEAD_BYTES = 16 * 1024
    const val IN_FLIGHT = 4
    const val READ_IDLE_MS = 120_000L
    const val READ_LIFETIME_MS = 3_600_000L
}
