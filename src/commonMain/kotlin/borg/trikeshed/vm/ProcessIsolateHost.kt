package borg.trikeshed.vm

import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.userspace.nio.channels.spi.ProcessProtocolBinding
import borg.trikeshed.userspace.nio.process.ProcessVmChannel

/** Compatibility name for a platform byte binding, not a second protocol reader or runtime. */
typealias ProcessPipe = ProcessProtocolBinding

/** Synchronous VM API over common userspace request, callback and process ownership. */
class ProcessIsolateHost(
    override val id: String,
    override val facet: VmFacet,
    private val pipe: ProcessPipe,
) : VmHandle {
    private val channel = ProcessVmChannel(pipe.context, pipe.pipes, invokeHost = { pipe.invoke(it) })
    override val tier: String get() = "process"
    override val isAlive: Boolean get() = channel.isAlive
    val processId: Long get() = channel.processId

    fun delegate(name: String, fn: (List<Teleported>) -> Teleported) = pipe.await { channel.delegate(name, fn) }
    override fun eval(source: String, name: String): Teleported = pipe.await { channel.eval(source, name) }
    override fun call(root: String, vararg args: Teleported): Teleported = pipe.await { channel.call(root, args.toList()) }
    fun interrupt(): Boolean = pipe.await { channel.interrupt() }
    override fun stats(): VmStats = channel.stats()
    override fun close() = pipe.await { channel.drain() }
}

class VmGuestException(val kind: String, message: String) : RuntimeException("$kind: $message")
