package gk.kademlia.agent.fsm

import borg.trikeshed.lib.first
import borg.trikeshed.lib.second
import borg.trikeshed.lib.third
import kotlinx.coroutines.Runnable
import java.net.InetSocketAddress
import java.nio.channels.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min
import borg.trikeshed.lib.Join3 as Tripl3
import borg.trikeshed.lib.j as t2

open class FSM(var topLevel: FsmNode? = null) : Runnable, AutoCloseable {

    private val q = ConcurrentLinkedQueue<Tripl3<SelectableChannel, Int, FsmNode>>()
    var selectorThread: Thread = Thread.currentThread()
    var selector: Selector = Selector.open()
    var timeoutMax: Long = 1024
    var timeout: Long = 1

    override fun run() {
        selectorThread = Thread.currentThread()

        while (selector.isOpen) {
            synchronized(q) {
                while (q.isNotEmpty()) {
                    val s = q.remove()
                    val x = s.first
                    val op = s.second
                    val att = s.third
                    try {
                        x.configureBlocking(false)
                        x.register(selector, op, att) ?: return@synchronized
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                }
            }
            val select = selector.select { key ->
                key.takeIf { it.isValid }?.apply {
                    val node: FsmNode =
                        (attachment() as? FsmNode ?: topLevel ?: TODO("toplevel builtin functions not yet implemented"))
                    node.process(this)?.let { qUp(it, this) }
                }
            }
            timeout = if (0 == select) min(timeout shl 1, timeoutMax)
            else 1
        }
    }


    override fun close() {
        try {
            selector.close()
        } finally {
        }
        selectorThread.interrupt()

    }

    /**
     * handles the threadlocal ugliness if any to registering user threads into the selector/reactor pattern
     *
     * @param fsmNode provides the needed selector interest
     * @param selectionKey the already registered key or null
     * @param channel the Channel when selection Key is null
     */

    fun qUp(
        fsmNode: FsmNode,
        selectionKey: SelectionKey? = null,
        channel: SelectableChannel? = selectionKey?.channel(),
    ) = try {
        channel?.takeIf { it.isOpen }?.run {
            if (Thread.currentThread() === selectorThread)
                register(selector, fsmNode.interest, fsmNode)
            else synchronized(q) { q.add(Tripl3.invoke(this, fsmNode.interest, fsmNode)) }
            selector.wakeup()
        }
    } catch (_: Throwable) {
    }


    companion object {
        /**
         * handles the thread pool initialization tangle of concurrency and wait.
         *
         * returns Join of threadpool and FSM
         *
         * call exectuorservice.shutdown or fsm.close for the purposes of stopping the fsm at a minimum.
         *
         */
        @JvmStatic
        fun launch(
            top: FsmNode,
            executorService: ExecutorService = Executors.newCachedThreadPool(),
            inetSocketAddress: InetSocketAddress = InetSocketAddress(2112),
            channel: SelectableChannel = ServerSocketChannel.open(),
        ) = executorService.run {
            val agentFsm = FSM(top)
            submit {
                val channel1: SelectableChannel = channel.configureBlocking(false)
                (channel1 as? NetworkChannel)?.bind(inetSocketAddress)
                agentFsm.qUp(top, channel = channel1)
                submit(agentFsm)
            }
            val lock = Object()
            synchronized(lock) {
                while (!isShutdown) {
                    lock.wait(1000)
                }
                /**
                 * boilerplate shutdown code should be here.
                 */
                agentFsm.selector.close()//the kill switch
            }
            this t2 agentFsm
        }
    }
}