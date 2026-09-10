package borg.trikeshed.userspace

import borg.trikeshed.lib.Series
import borg.trikeshed.userspace.nio.ByteBuffer

/** Lifecycle adapter for the public singleton. Every platform stages through the common ring. */
internal class LiburingSession(
    private val openBackend: (Int) -> UserspaceChannelBackend = ::openUserspaceChannelBackend,
) : LiburingFacade {
    private var ring: EmulatedRing? = null

    private fun open(): LiburingFacade? = ring
    private fun notOpen(): Result<Unit> = Result.failure(IllegalStateException("ring is not open"))
    private fun notOpenInt(): Result<Int> = Result.failure(IllegalStateException("ring is not open"))

    override fun open(entries: Int, flags: Int): Result<Unit> = runCatching {
        check(ring == null) { "ring is already open" }
        require(entries > 0) { "entries must be positive" }
        require(flags == 0) { "setup flags are unsupported by the common facade" }
        val backend = openBackend(entries)
        val opened = EmulatedRing(backend)
        try { opened.open(entries, flags).getOrThrow() } catch (failure: Throwable) {
            backend.close()
            throw failure
        }
        ring = opened
    }

    override fun prepRead(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit> =
        open()?.prepRead(fd, bufAddress, len, offset, userData) ?: notOpen()
    override fun prepWrite(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit> =
        open()?.prepWrite(fd, bufAddress, len, offset, userData) ?: notOpen()
    override fun prepAccept(fd: Int, userData: Long): Result<Unit> =
        open()?.prepAccept(fd, userData) ?: notOpen()
    override fun prepConnect(fd: Int, addrPtr: Long, addrLen: Int, userData: Long): Result<Unit> =
        open()?.prepConnect(fd, addrPtr, addrLen, userData) ?: notOpen()
    override fun prepClose(fd: Int, userData: Long): Result<Unit> =
        open()?.prepClose(fd, userData) ?: notOpen()
    override fun prepFsync(fd: Int, userData: Long, datasync: Boolean): Result<Unit> =
        open()?.prepFsync(fd, userData, datasync) ?: notOpen()
    override fun prepFtruncate(fd: Int, size: Long, userData: Long): Result<Unit> =
        open()?.prepFtruncate(fd, size, userData) ?: notOpen()
    override fun prepSendmsg(fd: Int, msgHdrPtr: Long, flags: Int, userData: Long): Result<Unit> =
        open()?.prepSendmsg(fd, msgHdrPtr, flags, userData) ?: notOpen()
    override fun prepRecvmsg(fd: Int, msgHdrPtr: Long, flags: Int, userData: Long): Result<Unit> =
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
    override fun prepRenameat(oldDfd: Int, oldPath: String, newDfd: Int, newPath: String, flags: Int, userData: Long): Result<Unit> =
        open()?.prepRenameat(oldDfd, oldPath, newDfd, newPath, flags, userData) ?: notOpen()
    override fun prepUnlinkat(dfd: Int, path: String, flags: Int, userData: Long): Result<Unit> =
        open()?.prepUnlinkat(dfd, path, flags, userData) ?: notOpen()
    override fun prepMkdirat(dfd: Int, path: String, mode: Int, userData: Long): Result<Unit> =
        open()?.prepMkdirat(dfd, path, mode, userData) ?: notOpen()

    override fun registerBuffers(buffers: Series<MemoryMapping>): Result<Unit> =
        open()?.registerBuffers(buffers) ?: notOpen()
    override fun registerBuffers(buffers: List<ByteBuffer>): Result<Int> =
        open()?.registerBuffers(buffers) ?: notOpenInt()
    override fun unregisterBuffers(): Result<Unit> = open()?.unregisterBuffers() ?: notOpen()
    override fun registerFiles(fds: IntArray): Result<Int> = open()?.registerFiles(fds) ?: notOpenInt()
    override fun unregisterFiles(): Result<Unit> = open()?.unregisterFiles() ?: notOpen()
    override fun prepReadFixed(fd: Int, bufIndex: Int, len: Int, offset: Long, userData: Long): Result<Unit> =
        open()?.prepReadFixed(fd, bufIndex, len, offset, userData) ?: notOpen()
    override fun prepWriteFixed(fd: Int, bufIndex: Int, len: Int, offset: Long, userData: Long): Result<Unit> =
        open()?.prepWriteFixed(fd, bufIndex, len, offset, userData) ?: notOpen()

    override fun submit(): Result<Int> = open()?.submit() ?: notOpenInt()
    override fun waitCqe(): Result<UringCompletion?> =
        open()?.waitCqe() ?: Result.failure(IllegalStateException("ring is not open"))
    override fun peekCqe(): Result<UringCompletion?> =
        open()?.peekCqe() ?: Result.failure(IllegalStateException("ring is not open"))
    override fun cqAdvance(count: Int) { open()?.cqAdvance(count) }

    override fun registerFanoutHandler(token: Long, handler: (UringCompletion) -> Unit) {
        ring?.registerFanoutHandler(token, handler)
    }
    override fun removeFanoutHandler(token: Long, handler: (UringCompletion) -> Unit) {
        ring?.removeFanoutHandler(token, handler)
    }

    override fun drain(): Result<Unit> = open()?.drain() ?: notOpen()
    override fun close(): Result<Unit> =
        (open()?.close() ?: Result.success(Unit)).also { if (it.isSuccess || ring?.isClosed == true) ring = null }
}
