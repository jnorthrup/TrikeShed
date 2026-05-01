package borg.trikeshed.uring

import borg.trikeshed.context.LiburingFacadeSpi
import borg.trikeshed.context.UringCompletion

class LiburingFacadeProvider : LiburingFacadeSpi {
    override fun open(entries: Int, flags: Int): Result<Unit> = Result.success(Unit)

    override fun prepRead(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit> = Result.success(Unit)

    override fun prepWrite(fd: Int, bufAddress: Long, len: Int, offset: Long, userData: Long): Result<Unit> = Result.success(Unit)

    override fun prepAccept(fd: Int, userData: Long): Result<Unit> = Result.success(Unit)

    override fun prepConnect(fd: Int, addrPtr: Long, addrLen: Int, userData: Long): Result<Unit> = Result.success(Unit)

    override fun prepClose(fd: Int, userData: Long): Result<Unit> = Result.success(Unit)

    override fun submit(): Result<Int> = Result.success(0)

    override fun waitCqe(): Result<UringCompletion> = Result.success(UringCompletion(0, 0, 0))

    override fun peekCqe(): Result<UringCompletion?> = Result.success(null)

    override fun cqAdvance(count: Int) {}

    override fun registerFanoutHandler(token: Long, handler: (UringCompletion) -> Unit) {}

    override fun removeFanoutHandler(token: Long, handler: (UringCompletion) -> Unit) {}

    override fun drain(): Result<Unit> = Result.success(Unit)

    override fun close(): Result<Unit> = Result.success(Unit)
}
