package borg.trikeshed.userspace

/**
 * The macOS binding.
 *
 * There is no native liburing here, so the facade is served by [EmulatedRing] over whatever
 * `openUserspaceChannelBackend` selects. Answering "liburing unavailable" would push every caller
 * back to the platform's own IO library, which is the creep the single vocabulary exists to end.
 *
 * The backend call is where a real ring is chosen when the host has one -- there is none on Darwin, so it is always the POSIX emulation -- this file does
 * not decide that and does not need to know.
 */
internal actual object LiburingImpl : LiburingFacade {
    private var ring: EmulatedRing? = null

    private fun open(): EmulatedRing? = ring
    private fun notOpen(): Result<Unit> = Result.failure(IllegalStateException("ring is not open"))

    actual override fun open(entries: Int, flags: Int): Result<Unit> = runCatching {
        require(entries > 0)
        ring = EmulatedRing(openUserspaceChannelBackend(entries))
    }

    actual override fun prepRead(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit> =
        open()?.prepRead(fd, bufAddress, len, offset, userData) ?: notOpen()
    actual override fun prepWrite(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit> =
        open()?.prepWrite(fd, bufAddress, len, offset, userData) ?: notOpen()
    actual override fun prepAccept(fd: Int, userData: Long): Result<Unit> =
        open()?.prepAccept(fd, userData) ?: notOpen()
    actual override fun prepConnect(fd: Int, addrPtr: Long, addrLen: Int, userData: Long): Result<Unit> =
        open()?.prepConnect(fd, addrPtr, addrLen, userData) ?: notOpen()
    actual override fun prepClose(fd: Int, userData: Long): Result<Unit> =
        open()?.prepClose(fd, userData) ?: notOpen()
    actual override fun prepFsync(fd: Int, userData: Long, datasync: Boolean): Result<Unit> =
        open()?.prepFsync(fd, userData, datasync) ?: notOpen()
    actual override fun prepFtruncate(fd: Int, size: Long, userData: Long): Result<Unit> =
        open()?.prepFtruncate(fd, size, userData) ?: notOpen()
    actual override fun prepMmap(fd: Int, addr: Long, len: Int, prot: Int, flags: Int, offset: Long, userData: Long): Result<Unit> =
        open()?.prepMmap(fd, addr, len, prot, flags, offset, userData) ?: notOpen()
    actual override fun prepMunmap(addr: Long, len: Int, userData: Long): Result<Unit> =
        open()?.prepMunmap(addr, len, userData) ?: notOpen()
    actual override fun prepSendmsg(fd: Int, msgHdrPtr: Long, flags: Int, userData: Long): Result<Unit> =
        open()?.prepSendmsg(fd, msgHdrPtr, flags, userData) ?: notOpen()
    actual override fun prepRecvmsg(fd: Int, msgHdrPtr: Long, flags: Int, userData: Long): Result<Unit> =
        open()?.prepRecvmsg(fd, msgHdrPtr, flags, userData) ?: notOpen()

    override fun prepOpenat(dfd: Int, path: String, flags: Int, mode: Int, userData: Long): Result<Unit> =
        open()?.prepOpenat(dfd, path, flags, mode, userData) ?: notOpen()
    override fun prepStatx(dfd: Int, path: String, flags: Int, mask: Int, bufAddress: Long, userData: Long): Result<Unit> =
        open()?.prepStatx(dfd, path, flags, mask, bufAddress, userData) ?: notOpen()
    override fun prepFallocate(fd: Int, mode: Int, offset: Long, len: Long, userData: Long): Result<Unit> =
        open()?.prepFallocate(fd, mode, offset, len, userData) ?: notOpen()
    override fun prepFadvise(fd: Int, offset: Long, len: Int, advice: Int, userData: Long): Result<Unit> =
        open()?.prepFadvise(fd, offset, len, advice, userData) ?: notOpen()
    override fun prepMadvise(addr: Long, length: Int, advice: Int, userData: Long): Result<Unit> =
        open()?.prepMadvise(addr, length, advice, userData) ?: notOpen()
    override fun prepMsync(addr: Long, length: Int, flags: Int, userData: Long): Result<Unit> =
        open()?.prepMsync(addr, length, flags, userData) ?: notOpen()
    override fun prepRenameat(oldDfd: Int, oldPath: String, newDfd: Int, newPath: String, flags: Int, userData: Long): Result<Unit> =
        open()?.prepRenameat(oldDfd, oldPath, newDfd, newPath, flags, userData) ?: notOpen()
    override fun prepUnlinkat(dfd: Int, path: String, flags: Int, userData: Long): Result<Unit> =
        open()?.prepUnlinkat(dfd, path, flags, userData) ?: notOpen()
    override fun prepMkdirat(dfd: Int, path: String, mode: Int, userData: Long): Result<Unit> =
        open()?.prepMkdirat(dfd, path, mode, userData) ?: notOpen()

    actual override fun submit(): Result<Int> =
        open()?.submit() ?: Result.failure(IllegalStateException("ring is not open"))
    actual override fun waitCqe(): Result<UringCompletion?> =
        open()?.waitCqe() ?: Result.failure(IllegalStateException("ring is not open"))
    actual override fun peekCqe(): Result<UringCompletion?> =
        open()?.peekCqe() ?: Result.failure(IllegalStateException("ring is not open"))
    actual override fun cqAdvance(count: Int) { open()?.cqAdvance(count) }
    actual override fun registerFanoutHandler(token: Long, handler: (UringCompletion) -> Unit) {}
    actual override fun removeFanoutHandler(token: Long, handler: (UringCompletion) -> Unit) {}
    actual override fun drain(): Result<Unit> = open()?.drain() ?: notOpen()
    actual override fun close(): Result<Unit> = (open()?.close() ?: Result.success(Unit)).also { ring = null }
}
