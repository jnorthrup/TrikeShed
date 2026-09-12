package borg.trikeshed.userspace

/** Requested execution depth; the target's compile-time ceiling still applies. */
enum class UringBackendMode {
    AUTO, NATIVE, EMULATED;

    companion object {
        fun parse(value: String?): UringBackendMode = when (value?.trim()?.lowercase()) {
            null, "", "auto" -> AUTO
            "native" -> NATIVE
            "emulated" -> EMULATED
            else -> throw IllegalArgumentException("TRIKESHED_URING_MODE must be auto, native, or emulated: $value")
        }
    }
}
