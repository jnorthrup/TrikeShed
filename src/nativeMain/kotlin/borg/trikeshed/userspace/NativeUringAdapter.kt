package borg.trikeshed.userspace

// io_uring native stub adapter required by the feature request.
object NativeUringAdapter {
    val isAvailable: Boolean = false
    fun initialize() {}
}
