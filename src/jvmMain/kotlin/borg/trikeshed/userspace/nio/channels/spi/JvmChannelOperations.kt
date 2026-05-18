package borg.trikeshed.userspace.nio.channels.spi

import borg.trikeshed.userspace.nio.ByteBuffer

class JvmChannelOperations : ChannelOperations {
    override fun openChannel(entries: Int): ChannelOperations.ChannelHandle = JvmChannelHandle()

    override fun socket(domain: Int, type: Int, protocol: Int): Int {
        val ch = if (type == 1) { // SOCK_STREAM
            java.nio.channels.SocketChannel.open()
        } else {
            java.nio.channels.ServerSocketChannel.open()
        }
        ch.configureBlocking(false)
        return JvmReactorOperations.bindChannel(ch, emptySet())
    }

    override fun bind(fd: Int, port: Int): Int {
        val ch = JvmReactorOperations.channelToFd.entries.firstOrNull { it.value == fd }?.key as? java.nio.channels.ServerSocketChannel ?: return -1
        ch.bind(java.net.InetSocketAddress(port))
        return 0
    }

    override fun listen(fd: Int, backlog: Int): Int = 0

    override fun accept(fd: Int): Int {
        val srv = JvmReactorOperations.channelToFd.entries.firstOrNull { it.value == fd }?.key as? java.nio.channels.ServerSocketChannel ?: return -1
        val client = srv.accept() ?: return -1
        client.configureBlocking(false)
        return JvmReactorOperations.bindChannel(client, emptySet())
    }

    override fun connect(fd: Int, host: String, port: Int): Int {
        val ch = JvmReactorOperations.channelToFd.entries.firstOrNull { it.value == fd }?.key as? java.nio.channels.SocketChannel ?: return -1
        try {
            ch.configureBlocking(true)
            ch.connect(java.net.InetSocketAddress(host, port))
        } catch (e: Exception) {
            return -1
        } finally {
            ch.configureBlocking(false)
        }
        return 0
    }

    override fun close(fd: Int): Int {
        val ch = JvmReactorOperations.channelToFd.entries.firstOrNull { it.value == fd }?.key ?: return -1
        ch.close()
        JvmReactorOperations.channelToFd.remove(ch)
        return 0
    }

    private class JvmChannelHandle : ChannelOperations.ChannelHandle {
        override val id: Int get() = 0
        private data class PendingOp(val fd: Int, val buf: ByteBuffer, val read: Boolean, val user: Long)
        private val pending = mutableListOf<PendingOp>()
        private var lastResults = mutableListOf<ChannelResult>()

        override fun read(buffer: ByteBuffer, offset: Long): Int = -1
        override fun write(buffer: ByteBuffer, offset: Long): Int = -1

        override fun readv(fd: Int, buffer: ByteBuffer, userData: Long): Int {
            pending.add(PendingOp(fd, buffer, read = true, user = userData))
            return 0
        }

        override fun writev(fd: Int, buffer: ByteBuffer, userData: Long): Int {
            pending.add(PendingOp(fd, buffer, read = false, user = userData))
            return 0
        }

        override fun submit(): Int {
            val completions = mutableListOf<ChannelResult>()
            for (op in pending) {
                val ch = JvmReactorOperations.channelToFd.entries.firstOrNull { it.value == op.fd }?.key as? java.nio.channels.SocketChannel
                if (ch == null) {
                    completions.add(ChannelResult(op.fd, -1, op.user))
                    continue
                }

                if (ch.isConnectionPending) {
                    try {
                        ch.finishConnect()
                    } catch (e: Exception) {
                        completions.add(ChannelResult(op.fd, -1, op.user))
                        continue
                    }
                }

                val jdkBuf = java.nio.ByteBuffer.wrap(op.buf.array(), op.buf.arrayOffset() + op.buf.position(), op.buf.remaining())
                val res = try {
                    if (op.read) {
                        val n = ch.read(jdkBuf)
                        if (n > 0) {
                            op.buf.position(op.buf.position() + n)
                        }
                        n
                    } else {
                        val n = ch.write(jdkBuf)
                        if (n > 0) {
                            op.buf.position(op.buf.position() + n)
                        }
                        n
                    }
                } catch (e: Exception) {
                    if (e is IllegalStateException) throw e
                    throw IllegalStateException("DEBUG: operation threw exception", e)
                }
                completions.add(ChannelResult(op.fd, res, op.user))
            }
            val n = pending.size
            pending.clear()
            lastResults = completions
            return n
        }

        override fun wait(minComplete: Int): List<ChannelResult> = lastResults
    }
}
