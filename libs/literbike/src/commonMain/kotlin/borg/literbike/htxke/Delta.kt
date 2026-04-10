package borg.literbike.htxke

import kotlin.collections.HashMap

/**
 * CCEK Protocol Delta - Multiple inlets, tributaries, outflows per protocol
 *
 * Like a river delta, each protocol has:
 * - INLETS: incoming data sources
 * - TRIBUTARIES: branching sub-streams
 * - OUTFLOWS: outgoing data sinks
 */

/**
 * Inlet - transmit end of a delta channel
 */
class Inlet<T>(val tx: ChannelTx<T>) {
    companion object {
        fun <T> new(capacity: Int): Inlet<T> {
            val channel = Channel<T>(capacity)
            return Inlet(channel.tx)
        }
    }

    suspend fun send(value: T): Result<Unit> = tx.send(value)

    fun trySend(value: T): Result<Unit> = tx.trySend(value)
}

/**
 * Outflow - receive end of a delta channel
 */
class Outflow<T>(val rx: ChannelRx<T>) {
    companion object {
        fun <T> new(capacity: Int): Outflow<T> {
            val channel = Channel<T>(capacity)
            return Outflow(channel.rx)
        }
    }

    suspend fun receive(): T? = rx.receive()

    fun tryReceive(): T? = rx.tryReceive()
}

/**
 * Tributary - channel with both inlet and outflow
 */
class Tributary<T>(private val channel: Channel<T>) {
    companion object {
        fun <T> new(capacity: Int): Tributary<T> = Tributary(Channel(capacity))
    }

    fun inlet(): Inlet<T> = Inlet(channel.tx)

    fun outflow(): Outflow<T> = Outflow(channel.rx)

    fun split(): Pair<Inlet<T>, Outflow<T>> = inlet() to outflow()
}

/**
 * Delta - collection of inlets, tributaries, and outflows for a protocol
 */
class Delta<T> {
    private val inlets = HashMap<String, ChannelTx<T>>()
    private val tributaries = HashMap<String, Channel<T>>()
    private val outflows = HashMap<String, ChannelRx<T>>()

    companion object {
        fun <T> new(): Delta<T> = Delta()
    }

    fun addInlet(name: String, capacity: Int): Delta<T> {
        val ch = Channel<T>(capacity)
        inlets[name] = ch.tx
        return this
    }

    fun addTributary(name: String, capacity: Int): Delta<T> {
        val ch = Channel<T>(capacity)
        tributaries[name] = ch
        return this
    }

    fun addOutflow(name: String, capacity: Int): Delta<T> {
        val ch = Channel<T>(capacity)
        outflows[name] = ch.rx
        return this
    }

    fun inlet(name: String): Inlet<T>? = inlets[name]?.let { Inlet(it) }

    fun tributary(name: String): Tributary<T>? = tributaries[name]?.let { Tributary(it) }

    fun outflow(name: String): Outflow<T>? = outflows[name]?.let { Outflow(it) }
}

/**
 * ProtocolDelta interface - protocols implement this to expose delta channels
 */
interface ProtocolDelta<T> {
    val delta: Delta<T>

    fun inlet(name: String): Inlet<T>? = delta.inlet(name)

    fun tributary(name: String): Tributary<T>? = delta.tributary(name)

    fun outflow(name: String): Outflow<T>? = delta.outflow(name)
}
