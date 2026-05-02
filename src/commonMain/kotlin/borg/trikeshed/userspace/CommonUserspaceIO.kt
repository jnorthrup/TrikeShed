package borg.trikeshed.userspace

class CommonUserspaceBuffer(private val backing: ByteArray) {
    val size: Int get() = backing.size

    fun get(index: Int): Byte = backing[index]

    fun put(index: Int, value: Byte) {
        backing[index] = value
    }
}

class CommonUserspaceFD(val id: Int) {
    fun isInvalid(): Boolean = id < 0
}

class CommonUserspaceFdAllocator(start: Int = 1) {
    private var nextFd = start

    fun next(): Int = nextFd++
}

class CommonUserspaceRing : UserspaceRing {
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
