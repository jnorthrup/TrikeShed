@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused", "NonAsciiCharacters")

package borg.trikeshed.userspace.nio.channels

import borg.trikeshed.userspace.Channel
import borg.trikeshed.userspace.Channels
import borg.trikeshed.userspace.File
import borg.trikeshed.userspace.nio.channels.spi.AbstractSelectableChannel
import borg.trikeshed.userspace.nio.channels.spi.SelectorProvider

/**
 * ServerSocketChannel wired behind UringFacade.
 *
 * bind/accept route through Channel → ChannelImpl → UringFacade.
 */
public abstract class ServerSocketChannel : AbstractSelectableChannel, NetworkChannel {
    constructor(provider: SelectorProvider) : super(provider)
    public abstract override fun validOps(): Int
    public abstract override fun bind(address: String): ServerSocketChannel
    fun bind(address: String, backlog: Int): ServerSocketChannel = bind(address)
    public abstract override fun <T> setOption(option: String, value: T): ServerSocketChannel
    // TODO
    abstract fun accept(): SocketChannel
    public abstract override fun getLocalAddress(): String

    companion object {
        fun open(): ServerSocketChannel {
            val file = Channels.socket(SocketDomain.AF_INET.posix, SocketType.SOCK_STREAM.mask, SocketProtocol.IPPROTO_TCP.posix)
            val channel = Channels.open()
            return UringServerSocketChannel(file, channel)
        }

        fun open(protocolFamily: String): ServerSocketChannel = open()
    }
}

internal class UringServerSocketChannel(
    private val file: File,
    private val channel: Channel,
) : ServerSocketChannel(SelectorProvider.provider()) {
    private var nextToken: Long = 1
    private var open: Boolean = true
    private val lock = Any()

    // TODO
    override fun begin() {}
    // TODO
    override fun end(completed: Boolean) {}

    override fun provider(): SelectorProvider = SelectorProvider.provider()
    override fun isRegistered(): Boolean = false
    override fun keyFor(sel: Selector): SelectionKey = throw IllegalStateException("not registered")
    override fun register(sel: Selector, ops: Int, att: Any): SelectionKey = throw UnsupportedOperationException("selector not supported — use uring")
    override fun register(sel: Selector, ops: Int): SelectionKey = register(sel, ops, Unit)
    override fun isBlocking(): Boolean = true
    override fun blockingLock(): Any = lock
    override fun configureBlocking(block: Boolean): SelectableChannel = this

    override fun validOps(): Int = SelectionKey.OP_ACCEPT

    override fun bind(address: String): ServerSocketChannel = this

    override fun <T> setOption(option: String, value: T): ServerSocketChannel = this

    override fun getLocalAddress(): String = "0.0.0.0:0"

    // TODO
    override fun accept(): SocketChannel = TODO("accept — Channel.accept + submit+wait")

    override fun <T> getOption(option: String): T = TODO("getOption")
    override fun supportedOptions(): Set<String> = emptySet()

    override fun close() { open = false }
    override fun isOpen(): Boolean = open

    override fun implCloseSelectableChannel() { open = false }
    // TODO
    override fun implConfigureBlocking(block: Boolean) {}
}
