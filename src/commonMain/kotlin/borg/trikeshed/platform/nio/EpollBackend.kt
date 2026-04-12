package borg.trikeshed.platform.nio

class EpollPlatformBackend(config: BackendConfig) : PlatformBackend {
    private val registrations = mutableMapOf<Int, Token>()
    private val pendingCompletions = mutableListOf<Completion>()

    override fun register(fd: Int, token: Token, interest: Interest) { registrations[fd] = token }
    override fun reregister(fd: Int, token: Token, interest: Interest) { registrations[fd] = token }
    override fun unregister(fd: Int) { registrations.remove(fd) }
    override fun submitRead(fd: Int, buf: ByteArray, userData: Long) { pendingCompletions.add(Completion(userData, Result.success(0), OpType.Read)) }
    override fun submitWrite(fd: Int, buf: ByteArray, userData: Long) { pendingCompletions.add(Completion(userData, Result.success(0), OpType.Write)) }
    override fun submitReadAt(fd: Int, offset: Long, buf: ByteArray, userData: Long) { pendingCompletions.add(Completion(userData, Result.success(0), OpType.Read)) }
    override fun submitWriteAt(fd: Int, offset: Long, buf: ByteArray, userData: Long) { pendingCompletions.add(Completion(userData, Result.success(0), OpType.Write)) }
    override fun submitPoll(fd: Int, interest: Interest, userData: Long) { pendingCompletions.add(Completion(userData, Result.success(0), OpType.PollAdd)) }
    override fun submitNop(userData: Long) { pendingCompletions.add(Completion(userData, Result.success(0), OpType.Nop)) }
    override fun submit(): Result<Long> { val n = pendingCompletions.size.toLong(); return Result.success(n) }
    override fun wait(min: Int): Result<Long> = submit()
    override fun peek(): Result<Long> = submit()
    override fun pollCompletion(): Result<Completion?> = Result.success(pendingCompletions.removeLastOrNull())
    override fun pollCompletions(completions: Array<Completion?>): Result<Int> {
        val count = minOf(completions.size, pendingCompletions.size)
        for (i in 0 until count) completions[i] = pendingCompletions.removeAt(0)
        return Result.success(count)
    }
    override fun asRawFd(): Int? = null
}
