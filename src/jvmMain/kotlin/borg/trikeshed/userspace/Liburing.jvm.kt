package borg.trikeshed.userspace

import borg.trikeshed.context.loadUserspaceNioSpi

/**
 * JVM actual for [LiburingImpl].
 *
 * Resolves a [UserspaceNioSpi] via ServiceLoader; if its `liburing` surface is non-null
 * (i.e. implements [UserspaceNioSpi.LiburingSurface]), calls are forwarded.
 * Otherwise every call is non-fatal [unsupported].
 */
internal actual object LiburingImpl : LiburingFacade {
    private val delegate: LiburingFacade? by lazy {
        runCatching { loadUserspaceNioSpi().liburing }.getOrNull()
    }

    actual override fun open(entries: Int, flags: Int): Result<Unit> =
        delegate?.open(entries, flags) ?: Result.failure(UnsupportedOperationException("liburing unavailable"))

    actual override fun prepRead(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit> =
        delegate?.prepRead(fd, bufAddress, len, offset, userData) ?: Result.failure(UnsupportedOperationException("liburing unavailable"))

    actual override fun prepWrite(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit> =
        delegate?.prepWrite(fd, bufAddress, len, offset, userData) ?: Result.failure(UnsupportedOperationException("liburing unavailable"))

    actual override fun prepAccept(fd: Int, userData: Long): Result<Unit> =
        delegate?.prepAccept(fd, userData) ?: Result.failure(UnsupportedOperationException("liburing unavailable"))

    actual override fun prepConnect(fd: Int, addrPtr: Long, addrLen: Int, userData: Long): Result<Unit> =
        delegate?.prepConnect(fd, addrPtr, addrLen, userData) ?: Result.failure(UnsupportedOperationException("liburing unavailable"))

    actual override fun prepClose(fd: Int, userData: Long): Result<Unit> =
        delegate?.prepClose(fd, userData) ?: Result.failure(UnsupportedOperationException("liburing unavailable"))

    actual override fun prepFsync(fd: Int, userData: Long, datasync: Boolean): Result<Unit> =
        delegate?.prepFsync(fd, userData, datasync) ?: Result.failure(UnsupportedOperationException("liburing unavailable"))

    actual override fun prepFtruncate(fd: Int, size: Long, userData: Long): Result<Unit> =
        delegate?.prepFtruncate(fd, size, userData) ?: Result.failure(UnsupportedOperationException("liburing unavailable"))

    actual override fun prepMmap(fd: Int, addr: Long, len: Int, prot: Int, flags: Int, offset: Long, userData: Long): Result<Unit> =
        delegate?.prepMmap(fd, addr, len, prot, flags, offset, userData) ?: Result.failure(UnsupportedOperationException("liburing unavailable"))

    actual override fun prepMunmap(addr: Long, len: Int, userData: Long): Result<Unit> =
        delegate?.prepMunmap(addr, len, userData) ?: Result.failure(UnsupportedOperationException("liburing unavailable"))

    actual override fun prepSendmsg(fd: Int, msgHdrPtr: Long, flags: Int, userData: Long): Result<Unit> =
        delegate?.prepSendmsg(fd, msgHdrPtr, flags, userData) ?: Result.failure(UnsupportedOperationException("liburing unavailable"))

    actual override fun prepRecvmsg(fd: Int, msgHdrPtr: Long, flags: Int, userData: Long): Result<Unit> =
        delegate?.prepRecvmsg(fd, msgHdrPtr, flags, userData) ?: Result.failure(UnsupportedOperationException("liburing unavailable"))

    actual override fun submit(): Result<Int> =
        delegate?.submit() ?: Result.failure(UnsupportedOperationException("liburing unavailable"))

    actual override fun waitCqe(): Result<UringCompletion?> =
        delegate?.waitCqe() ?: Result.failure(UnsupportedOperationException("liburing unavailable"))

    actual override fun peekCqe(): Result<UringCompletion?> =
        delegate?.peekCqe() ?: Result.failure(UnsupportedOperationException("liburing unavailable"))

    actual override fun cqAdvance(count: Int) {
        delegate?.cqAdvance(count)
    }

    actual override fun registerFanoutHandler(token: Long, handler: (UringCompletion) -> Unit) {
        delegate?.registerFanoutHandler(token, handler)
    }

    actual override fun removeFanoutHandler(token: Long, handler: (UringCompletion) -> Unit) {
        delegate?.removeFanoutHandler(token, handler)
    }

    actual override fun drain(): Result<Unit> = delegate?.drain() ?: Result.failure(UnsupportedOperationException("liburing unavailable"))
    actual override fun close(): Result<Unit> = delegate?.close() ?: Result.failure(UnsupportedOperationException("liburing unavailable"))

}