package borg.trikeshed.userspace.nio

import borg.trikeshed.context.NioUserspaceElement
import borg.trikeshed.context.UserspaceNioSpi
import borg.trikeshed.userspace.reactor.Interest
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

class NioSpiBackend(
    private val provider: UserspaceNioSpi = UserspaceNioProvider()
) : PlatformBackend {
    private val elements = ConcurrentHashMap<Int, NioUserspaceElement>()
    private val registrations = ConcurrentHashMap<Int, Pair<Long, Interest>>()
    private val completions = ConcurrentLinkedQueue<Completion>()
    private val submittedCount = AtomicLong(0)

    private fun elementFor(fd: Int): NioUserspaceElement =
        elements.computeIfAbsent(fd) { openedFd ->
            runBlocking { provider.open(openedFd) }
        }

    override fun register(fd: Int, token: Long, interest: Interest): Result<Unit> = runCatching {
        elementFor(fd)
        registrations[fd] = token to interest
    }

    override fun reregister(fd: Int, token: Long, interest: Interest): Result<Unit> = runCatching {
        elementFor(fd)
        registrations[fd] = token to interest
    }

    override fun unregister(fd: Int): Result<Unit> = runCatching {
        registrations.remove(fd)
        elements.remove(fd)?.let { element ->
            runBlocking { provider.close(element) }
        }
    }

    override fun submitRead(fd: Int, buf: ByteArray, userData: Long): Result<Unit> = runCatching {
        val element = elementFor(fd)
        val completion = Completion(userData, Result.success(buf.size), OpType.Read)
        completions.add(completion)
        submittedCount.incrementAndGet()
        runBlocking { provider.fanout(completion, listOf(element)) }
    }

    override fun submitWrite(fd: Int, buf: ByteArray, userData: Long): Result<Unit> = runCatching {
        val element = elementFor(fd)
        val completion = Completion(userData, Result.success(buf.size), OpType.Write)
        completions.add(completion)
        submittedCount.incrementAndGet()
        runBlocking { provider.fanout(completion, listOf(element)) }
    }

    override fun submit(): Result<Long> = runCatching {
        submittedCount.getAndSet(0)
    }

    override fun wait(min: Int): Result<Long> = runCatching {
        completions.size.toLong()
    }

    override fun pollCompletion(): Result<Completion?> = runCatching {
        completions.poll()
    }
}
