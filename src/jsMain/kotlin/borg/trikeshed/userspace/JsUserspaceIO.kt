package borg.trikeshed.userspace

actual class UserspaceBuffer(private val backing: ByteArray) {
    actual val size: Int get() = backing.size
    actual fun get(index: Int): Byte = backing[index]
    actual fun put(index: Int, value: Byte) {
        backing[index] = value
    }
}

actual class UserspaceFD(actual val id: Int) {
    actual fun isInvalid(): Boolean = id < 0
}

private class JsUserspaceRing : UserspaceRing {
    private val pendingResults = mutableListOf<UserspaceIOResult>()

    override fun prepRead(fd: UserspaceFD, buffer: UserspaceBuffer, offset: Long, userData: Long) {
        pendingResults.add(UserspaceIOResult(-1, userData))
    }

    override fun prepWrite(fd: UserspaceFD, buffer: UserspaceBuffer, offset: Long, userData: Long) {
        pendingResults.add(UserspaceIOResult(-1, userData))
    }

    override fun prepAccept(fd: UserspaceFD, userData: Long) {
        pendingResults.add(UserspaceIOResult(-1, userData))
    }

    override fun prepConnect(fd: UserspaceFD, address: String, port: Int, userData: Long) {
        pendingResults.add(UserspaceIOResult(-1, userData))
    }

    override fun prepClose(fd: UserspaceFD, userData: Long) {
        pendingResults.add(UserspaceIOResult(0, userData))
    }

    override fun submit(): Int = pendingResults.size

    override fun wait(minComplete: Int): List<UserspaceIOResult> = peek()

    override fun peek(): List<UserspaceIOResult> {
        val snapshot = pendingResults.toList()
        pendingResults.clear()
        return snapshot
    }
}

private object JsUserspaceSPI : UserspaceSPI {
    private var nextFd = 1

    override fun createRing(entries: Int): UserspaceRing = JsUserspaceRing()

    override fun openFile(path: String, readOnly: Boolean): UserspaceFD = UserspaceFD(nextFd++)

    override fun createSocket(domain: Int, type: Int, protocol: Int): UserspaceFD = UserspaceFD(nextFd++)

    override fun wrapBuffer(byteArray: ByteArray): UserspaceBuffer = UserspaceBuffer(byteArray)
}

actual val userspaceSPI: UserspaceSPI = JsUserspaceSPI
