package borg.trikeshed.userspace

import borg.trikeshed.context.loadLiburingFacadeSpi

internal actual object LiburingImpl : LiburingFacade {
    private val delegate: LiburingFacade? by lazy {
        runCatching { loadLiburingFacadeSpi() as LiburingFacade }.getOrNull()
    }

    actual override fun open(entries: Int, flags: Int): Result<Unit> =
        delegate?.open(entries, flags) ?: unsupported()

    actual override fun prepRead(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit> =
        delegate?.prepRead(fd, bufAddress, len, offset, userData) ?: unsupported()

    actual override fun prepWrite(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit> =
        delegate?.prepWrite(fd, bufAddress, len, offset, userData) ?: unsupported()

    actual override fun prepAccept(fd: Int, userData: Long): Result<Unit> =
        delegate?.prepAccept(fd, userData) ?: unsupported()

    actual override fun prepConnect(fd: Int, addrPtr: Long, addrLen: Int, userData: Long): Result<Unit> =
        delegate?.prepConnect(fd, addrPtr, addrLen, userData) ?: unsupported()

    actual override fun prepClose(fd: Int, userData: Long): Result<Unit> =
        delegate?.prepClose(fd, userData) ?: unsupported()

    actual override fun prepFsync(fd: Int, userData: Long, datasync: Boolean): Result<Unit> =
        delegate?.prepFsync(fd, userData, datasync) ?: unsupported()

    actual override fun prepFtruncate(fd: Int, size: Long, userData: Long): Result<Unit> =
        delegate?.prepFtruncate(fd, size, userData) ?: unsupported()

    actual override fun prepMmap(fd: Int, addr: Long, len: Int, prot: Int, flags: Int, offset: Long, userData: Long): Result<Unit> =
        delegate?.prepMmap(fd, addr, len, prot, flags, offset, userData) ?: unsupported()

    actual override fun prepMunmap(addr: Long, len: Int, userData: Long): Result<Unit> =
        delegate?.prepMunmap(addr, len, userData) ?: unsupported()

    actual override fun submit(): Result<Int> = delegate?.submit() ?: unsupported()

    actual override fun waitCqe(): Result<UringCompletion> = delegate?.waitCqe() ?: unsupported()

    actual override fun peekCqe(): Result<UringCompletion?> = delegate?.peekCqe() ?: unsupported()

    actual override fun cqAdvance(count: Int) {
        delegate?.cqAdvance(count)
    }

    actual override fun registerFanoutHandler(token: Long, handler: (UringCompletion) -> Unit) {
        delegate?.registerFanoutHandler(token, handler)
    }

    actual override fun removeFanoutHandler(token: Long, handler: (UringCompletion) -> Unit) {
        delegate?.removeFanoutHandler(token, handler)
    }

    actual override fun drain(): Result<Unit> = delegate?.drain() ?: unsupported()

    actual override fun close(): Result<Unit> = delegate?.close() ?: unsupported()
}

private fun <T> unsupported(): Result<T> =
    Result.failure(UnsupportedOperationException("liburing facade is only available on linux"))
