package one.xio

import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.*

/**
 * User: jim
 * Date: 4/15/12
 * Time: 11:50 PM
 */
interface AsioVisitor {
    @Throws(Exception::class)
    fun onRead(key: SelectionKey)

    @Throws(Exception::class)
    fun onConnect(key: SelectionKey)

    @Throws(Exception::class)
    fun onWrite(key: SelectionKey)

    @Throws(Exception::class)
    fun onAccept(key: SelectionKey)

    class Impl : AsioVisitor {
        init {
            if (`$DBG`) `$origins`!![this] =
                HttpMethod.wheresWaldo(4)
        }

        fun preRead(vararg env: Any?): Impl {
            return this
        }

        fun preWrite(vararg env: Any?): Impl {
            return this
        }

        @Throws(Exception::class)
        override fun onRead(key: SelectionKey) {
            System.err.println("fail: $key")
            val channel = key.channel() as SocketChannel
            val receiveBufferSize = channel.socket().receiveBufferSize
            val trim =
                HttpMethod.UTF8.decode(ByteBuffer.allocateDirect(receiveBufferSize)).toString().trim { it <= ' ' }

            throw UnsupportedOperationException("found " + trim + " in " + javaClass.name)
        }

        /**
         * this doesn't change very often for outbound web connections
         *
         * @param key
         * @throws Exception
         */
        @Throws(Exception::class)
        override fun onConnect(key: SelectionKey) {
            if ((key.channel() as SocketChannel).finishConnect()) key.interestOps(SelectionKey.OP_WRITE)
        }

        @Throws(Exception::class)
        override fun onWrite(key: SelectionKey) {
            val channel = key.channel() as SocketChannel
            System.err.println("buffer underrun?: " + channel.socket().remoteSocketAddress)
            throw UnsupportedOperationException("found in " + javaClass.name)
        }

        @Throws(Exception::class)
        override fun onAccept(key: SelectionKey) {
            val c = key.channel() as ServerSocketChannel
            val accept = c.accept()
            accept.configureBlocking(false)
            HttpMethod.enqueue(accept, SelectionKey.OP_READ or SelectionKey.OP_WRITE, key.attachment())
        }
    }

    companion object {
        @JvmField
        val `$DBG`: Boolean = null != System.getenv("DEBUG_VISITOR_ORIGINS")
        @JvmField
        val `$origins`: WeakHashMap<Impl, String>? = if (`$DBG`) WeakHashMap() else null
    }
}
