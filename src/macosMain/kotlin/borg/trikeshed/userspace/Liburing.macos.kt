package borg.trikeshed.userspace

internal actual object LiburingImpl : LiburingFacade {
    actual override fun open(entries: Int, flags: Int): Result<Unit> = unsupported()
    actual override fun prepRead(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit> = unsupported()
    actual override fun prepWrite(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit> = unsupported()
    actual override fun prepAccept(fd: Int, userData: Long): Result<Unit> = unsupported()
    actual override fun prepConnect(fd: Int, addrPtr: Long, addrLen: Int, userData: Long): Result<Unit> = unsupported()
    actual override fun prepClose(fd: Int, userData: Long): Result<Unit> = unsupported()
    actual override fun prepFsync(fd: Int, userData: Long, datasync: Boolean): Result<Unit> = unsupported()
    actual override fun prepFtruncate(fd: Int, size: Long, userData: Long): Result<Unit> = unsupported()
    actual override fun prepMmap(fd: Int, addr: Long, len: Int, prot: Int, flags: Int, offset: Long, userData: Long): Result<Unit> = unsupported()
    actual override fun prepMunmap(addr: Long, len: Int, userData: Long): Result<Unit> = unsupported()
    actual override fun prepSendmsg(fd: Int, msgHdrPtr: Long, flags: Int, userData: Long): Result<Unit> = unsupported()
    actual override fun prepRecvmsg(fd: Int, msgHdrPtr: Long, flags: Int, userData: Long): Result<Unit> = unsupported()
    actual override fun submit(): Result<Int> = unsupported()
    actual override fun waitCqe(): Result<UringCompletion> = unsupported()
    actual override fun peekCqe(): Result<UringCompletion?> = unsupported()
    actual override fun cqAdvance(count: Int) {}
    actual override fun registerFanoutHandler(token: Long, handler: (UringCompletion) -> Unit) {}
    actual override fun removeFanoutHandler(token: Long, handler: (UringCompletion) -> Unit) {}
    actual override fun drain(): Result<Unit> = unsupported()
    actual override fun close(): Result<Unit> = unsupported()
}

private fun <T> unsupported(): Result<T> =
    Result.failure(UnsupportedOperationException("liburing facade is only available on linux"))
