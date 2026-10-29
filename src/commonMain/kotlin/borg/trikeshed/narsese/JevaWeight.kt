package borg.trikeshed.narsese

import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.platform.spi.MatrixSession
import kotlin.coroutines.CoroutineContext

/** Optional row-major bulk access on a tensor's existing scalar oracle; storage stays with its owner. */
interface JevaWeight : MetaSeries<Tensor, Float> {
    val size: Int

    /** Copy a contiguous span into caller-owned storage. Failure may leave a partial destination. */
    fun read(offset: Int, dst: FloatArray, start: Int = 0, length: Int = dst.size - start,
             check: () -> Unit = {})
}

/** The causal text backbone one inference runs: token ids to final-normalized hidden states [tokens, hidden]. */
interface JevaEncoder {
    fun encode(tokens: Series<Int>, check: () -> Unit = {}, progress: (Int, Int) -> Unit = { _, _ -> }): MetaSeries<Tensor, Float>
}

/** Named float tensors one inference owns: the checkpoint's head, and on the CPU path the backbone as well. */
interface JevaTensors {
    operator fun get(name: String): MetaSeries<Tensor, Float>
    val mappedBytes: Long
    fun close()
}

/** A platform's device backbone for one checkpoint directory, with the matrix session the head shares. */
interface EncoderSession : JevaEncoder {
    val hiddenSize: Int
    val matrix: MatrixSession
    /** Names the runtime and the weight files this backbone reads. */
    val identity: String
    fun close()
}

/** Provider of device backbones (MLX on macOS native); resolved from the coroutine context or the NIO services. */
interface EncoderOperations : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<EncoderOperations>
    override val key: CoroutineContext.Key<*> get() = Key
    fun open(directory: String): EncoderSession
}
