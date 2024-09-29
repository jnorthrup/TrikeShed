package one.xio

import one.xio.AsioVisitor.FSM
import one.xio.AsioVisitor.Helper
import one.xio.AsioVisitor.Impl
import java.io.IOException
import java.lang.StrictMath.min
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * singleton interface
 */
interface AsyncSingletonServer {
    /**
     * Created by jim per 5/19/14.
     */
    object SingleThreadSingletonServer : AsyncSingletonServer {
        /**
         * handles the threadlocal ugliness if any to registering user threads into the selector/reactor pattern
         *
         * @param channel the socketchanel
         * @param op      int ChannelSelector.operator
         * @param s       the payload: grammar {enum,data1,data..n}
         */
        fun enqueue(channel: SelectableChannel, op: Int, vararg s: Object?) {
            assert(channel != null && !killswitch.get()) { "Server appears to have shut down, cannot enqueue" }
            if (Thread.currentThread() === FSM.selectorThread) try {
                channel.register(Helper.getSelector(), op, s)
            } catch (e: ClosedChannelException) {
                e.printStackTrace()
            }
            else {
                q.add(arrayOf(channel, op, s))
            }
            val selector1: Selector? = Helper.getSelector()
            if (null != selector1) selector1.wakeup()
        }

        @kotlin.Throws(IOException::class)
        fun init(protocoldecoder: AsioVisitor?) {
            Helper.setSelector(Selector.open())
            FSM.selectorThread = Thread.currentThread()

            val timeoutMax: Long = 1024
            var timeout: Long = 1
            /*synchronized (killswitch)*/
            run {
                while (!killswitch.get()) {
                    while (!q.isEmpty()) {
                        val s: Array<Object> = q.remove()
                        val x: SelectableChannel = s.get(0) as SelectableChannel
                        val sel: Selector = Helper.getSelector()
                        val op: Integer = s.get(1) as Integer
                        val att: Object = s.get(2)

                        try {
                            x.configureBlocking(false)
                            val register: SelectionKey = kotlin.checkNotNull(x.register(sel, op, att))
                        } catch (e: Throwable) {
                            e.printStackTrace()
                        }
                    }
                    val select: Int = FSM.selector.select(timeout)

                    timeout = if (0 == select) min(timeout shl 1, timeoutMax) else 1
                    if (0 != select) innerloop(protocoldecoder)
                }
            }
        }

        @kotlin.Throws(IOException::class)
        fun innerloop(protocoldecoder: AsioVisitor?) {
            val keys: Set<SelectionKey> = FSM.selector.selectedKeys()

            val i: Iterator<SelectionKey> = keys.iterator()
            while (i.hasNext()) {
                val key: SelectionKey = i.next()
                i.remove()

                if (key.isValid()) {
                    val channel: SelectableChannel = key.channel()
                    try {
                        val m: AsioVisitor = inferAsioVisitor(protocoldecoder, key)

                        if (key.isValid() && key.isWritable()) {
                            if ((channel as SocketChannel).socket().isOutputShutdown()) {
                                key.cancel()
                            } else {
                                m.onWrite(key)
                            }
                        }
                        if (key.isValid() && key.isReadable()) {
                            if ((channel as SocketChannel).socket().isInputShutdown()) {
                                key.cancel()
                            } else {
                                m.onRead(key)
                            }
                        }
                        if (key.isValid() && key.isAcceptable()) {
                            m.onAccept(key)
                        }
                        if (key.isValid() && key.isConnectable()) {
                            m.onConnect(key)
                        }
                    } catch (e: Throwable) {
                        val attachment: Object = key.attachment()
                        if (attachment !is Array<Object>) {
                            System.err.println("BadHandler: " + String.valueOf(attachment))
                        } else {
                            System.err.println("BadHandler: " + attachment.contentDeepToString())
                        }
                        if (AsioVisitor.`$DBG`) {
                            val asioVisitor: AsioVisitor = inferAsioVisitor(protocoldecoder, key)
                            if (asioVisitor is Impl) {
                                val visitor: Impl = asioVisitor as Impl
                                if (AsioVisitor.`$origins`.containsKey(visitor)) {
                                    val s: String = AsioVisitor.`$origins`.get(visitor)
                                    System.err.println("origin" + s)
                                }
                            }
                        }
                        e.printStackTrace()
                        key.attach(null)
                        channel.close()
                    }
                }
            }
        }

        fun inferAsioVisitor(`default$`: AsioVisitor?, key: SelectionKey): AsioVisitor {
            var attachment: Object = key.attachment()
            val m: AsioVisitor
            if (null == attachment) if (attachment is Array<Object>) {
                for (o: Object in attachment) {
                    attachment = o
                    break
                }
            }
            if (attachment is Iterable) {
                for (o: Object in attachment) {
                    attachment = o
                    break
                }
            }
            m = if (attachment is AsioVisitor) attachment as AsioVisitor else `default$`
            return m
        }
    }

    companion object {
        val q: Queue<Array<Object>> = ConcurrentLinkedQueue()
        val killswitch: AtomicBoolean = AtomicBoolean()
    }
}
