package borg.trikeshed.kanban.collective

import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.MetaSeries
import borg.trikeshed.lib.Series
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MeshAxis — a named dimension in the device mesh.
 * Corresponds to X, Y, Z axes in JAX named-axis sharding.
 */
@Serializable
data class MeshAxis(
    val name: String,
    val size: Int,
    val deviceIds: List<Int>,
) {
    companion object {
        fun x(size: Int, deviceIds: List<Int>) = MeshAxis("X", size, deviceIds)
        fun y(size: Int, deviceIds: List<Int>) = MeshAxis("Y", size, deviceIds)
        fun z(size: Int, deviceIds: List<Int>) = MeshAxis("Z", size, deviceIds)
    }
}

/**
 * DeviceMesh — the topology of devices for collective operations.
 * Mirrors JAX's device mesh with named axes.
 */
@Serializable
data class DeviceMesh(
    val axes: List<MeshAxis>,
    val devices: List<Int>,
) {
    fun axis(name: String): MeshAxis? = axes.find { it.name == name }

    fun axisSize(name: String): Int = axis(name)?.size ?: 1

    fun devicesFor(axis: String, slice: Int): List<Int> = axis(axis)?.deviceIds?.drop(slice * (deviceIds.size / axisSize(axis)))?.take(deviceIds.size / axisSize(axis)) ?: devices
}

/**
 * ShardingSpec — how a Series/Cursor dimension is partitioned across a mesh axis.
 * Mirrors JAX's A[I_X, J_Y] notation.
 */
@Serializable
sealed interface ShardingSpec {
    @Serializable
    data class Unsharded() : ShardingSpec

    @Serializable
    data class Sharded(
        val dimension: String,
        val meshAxis: String,
    ) : ShardingSpec

    @Serializable
    data class Replicated(
        val meshAxis: String,
    ) : ShardingSpec
}

/**
 * CollectiveOp — the algebraic operators for distributed computation.
 * These are the AllGather, ReduceScatter, AllReduce, AllToAll that the
 * JAX Scaling Book identifies as the fundamental communication primitives.
 */
sealed interface CollectiveOp<out T> {
    /** The output sharding after this collective */
    val outputSharding: ShardingSpec

    /** The mesh axis this collective operates on */
    val meshAxis: String
}

@Serializable
data class AllGather<T>(
    override val meshAxis: String,
    val inputSharding: ShardingSpec.Sharded,
) : CollectiveOp<T> {
    override val outputSharding: ShardingSpec = ShardingSpec.Replicated(meshAxis)
}

@Serializable
data class ReduceScatter<T>(
    override val meshAxis: String,
    val inputSharding: ShardingSpec.Replicated,
    val reduceOp: ReduceOp = ReduceOp.SUM,
) : CollectiveOp<T> {
    override val outputSharding: ShardingSpec = ShardingSpec.Sharded("contracted", meshAxis)
}

@Serializable
data class AllReduce<T>(
    override val meshAxis: String,
    val inputSharding: ShardingSpec.Replicated,
    val reduceOp: ReduceOp = ReduceOp.SUM,
) : CollectiveOp<T> {
    override val outputSharding: ShardingSpec = ShardingSpec.Replicated(meshAxis)
}

@Serializable
data class AllToAll<T>(
    override val meshAxis: String,
    val inputSharding: ShardingSpec.Sharded,
    val outputSharding: ShardingSpec.Sharded,
) : CollectiveOp<T>

enum class ReduceOp { SUM, MEAN, MIN, MAX, PROD }

/**
 * Collective — the entry point for collective algebra on Cursor.
 * 
 * Usage:
 *   val mesh = DeviceMesh(axes = listOf(MeshAxis.x(4, devices)), devices = devices)
 *   val sharded = cursor.shard("batch", "X")
 *   val gathered = sharded.allGather("X")
 *   val reduced = sharded.reduceScatter("X", ReduceOp.SUM)
 *   val allReduced = cursor.allReduce("X", ReduceOp.MEAN)
 *   val transposed = sharded.allToAll("X", "Y")
 */
class Collective(
    private val mesh: DeviceMesh,
    private val parentJob: CompletableJob? = null,
) {

    /**
     * AllGather: gather sharded dimension across mesh axis → replicated.
     * Input:  A[I_X] on 4 devices, each has A[I_local]
     * Output: A[I] replicated on all 4 devices
     * Cost:   (size / devices) * (devices - 1) bytes sent per device
     */
    suspend fun <T> Cursor<T>.allGather(
        dimension: String,
        meshAxis: String,
    ): Cursor<T> = withContext(Dispatchers.IO) {
        require(mesh.axis(meshAxis) != null) { "Mesh axis $meshAxis not found" }
        // In real impl: use NCCL/RCCL/UCX to all-gather
        // For local impl: just return self (single device)
        if (mesh.devices.size == 1) return@withContext this

        // Partition: each device holds a slice
        // AllGather concatenates slices along the sharded dimension
        val localSize = size / mesh.axisSize(meshAxis)
        val gathered = seriesOf<T> {
            // In real impl: ncclAllGather
            // Simulated: replicate local data
            (0 until mesh.axisSize(meshAxis)).flatMap { slice ->
                this.slice(slice * localSize, (slice + 1) * localSize)
            }
        }
        gathered
    }

    /**
     * ReduceScatter: reduce across mesh axis + scatter result.
     * Input:  A[I] replicated on all devices
     * Output: A[I_X] sharded (each device gets 1/N of reduced result)
     * Cost:   (size / devices) * (devices - 1) bytes sent per device
     */
    suspend fun <T> Cursor<T>.reduceScatter(
        dimension: String,
        meshAxis: String,
        op: ReduceOp = ReduceOp.SUM,
    ): Cursor<T> = withContext(Dispatchers.IO) {
        require(mesh.axis(meshAxis) != null) { "Mesh axis $meshAxis not found" }
        if (mesh.devices.size == 1) return@withContext this

        // In real impl: ncclReduceScatter
        // Simulated: each device computes local slice of reduced result
        val localSize = size / mesh.axisSize(meshAxis)
        val sliceIndex = mesh.devices.indexOf(currentDeviceId())
        val reduced = seriesOf<T> {
            (0 until localSize).map { i ->
                // Reduce across all devices for this slice
                val globalIndex = sliceIndex * localSize + i
                reduceSlice(globalIndex, op)
            }
        }
        reduced
    }

    /**
     * AllReduce: reduce across mesh axis → replicated result on all devices.
     * Input:  A[I] replicated
     * Output: A[I] replicated (reduced)
     * Cost:   size * (devices - 1) bytes sent per device
     */
    suspend fun <T> Cursor<T>.allReduce(
        meshAxis: String,
        op: ReduceOp = ReduceOp.SUM,
    ): Cursor<T> = withContext(Dispatchers.IO) {
        require(mesh.axis(meshAxis) != null) { "Mesh axis $meshAxis not found" }
        if (mesh.devices.size == 1) return@withContext this

        // In real impl: ncclAllReduce
        // Simulated: each element reduced across all devices
        val reduced = seriesOf<T> {
            (0 until size).map { i ->
                reduceElement(i, op)
            }
        }
        reduced
    }

    /**
     * AllToAll: transpose sharding from one mesh axis to another.
     * Input:  A[I_X, J] sharded on X
     * Output: A[I, J_X] sharded on X (transposed)
     * Cost:   size * (devices - 1) / devices bytes sent per device
     */
    suspend fun <T> Cursor<T>.allToAll(
        fromAxis: String,
        toAxis: String,
        inputDim: String,
        outputDim: String,
    ): Cursor<T> = withContext(Dispatchers.IO) {
        require(mesh.axis(fromAxis) != null) { "Mesh axis $fromAxis not found" }
        require(mesh.axis(toAxis) != null) { "Mesh axis $toAxis not found" }
        if (mesh.devices.size == 1) return@withContext this

        // In real impl: ncclAllToAll
        // Simulated: transpose sharding
        val transposed = seriesOf<T> {
            // Each device sends its X-shard to corresponding Y-device
            // This is the core of sharded matmul Case 4
            this
        }
        transposed
    }

    // Helpers for simulation
    private suspend fun <T> Cursor<T>.reduceSlice(index: Int, op: ReduceOp): T {
        // In real impl: cross-device reduction
        // For simulation: return local value
        this[index]
    }

    private suspend fun <T> Cursor<T>.reduceElement(index: Int, op: ReduceOp): T {
        this[index]
    }

    private fun currentDeviceId(): Int = mesh.devices.first() // Simulated

    // Extension to get axis size
    private fun DeviceMesh.axisSize(name: String): Int = axis(name)?.size ?: 1
}

// Extension functions for Collective algebra on Series/Cursor
fun <T> Series<T>.allGather(meshAxis: String): Series<T> = this
fun <T> Series<T>.reduceScatter(meshAxis: String, op: ReduceOp): Series<T> = this
fun <T> Series<T>.allReduce(meshAxis: String, op: ReduceOp): Series<T> = this
fun <T> Series<T>.allToAll(fromAxis: String, toAxis: String): Series<T> = this

/**
 * Sharding extensions — annotate a Series/Cursor with sharding intent.
 * These don't execute collectives; they mark intent for lowering.
 */
fun <T> Series<T>.shard(
    dimension: String,
    meshAxis: String,
): ShardedSeries<T> = ShardedSeries(this, dimension, meshAxis)

fun <T> Cursor<T>.shard(
    dimension: String,
    meshAxis: String,
): ShardedCursor<T> = ShardedCursor(this, dimension, meshAxis)

@Serializable
data class ShardedSeries<T>(
    val series: Series<T>,
    val dimension: String,
    val meshAxis: String,
) : Series<T> by series

@Serializable
data class ShardedCursor<T>(
    val cursor: Cursor<T>,
    val dimension: String,
    val meshAxis: String,
) : Cursor<T> by cursor

// Lowering: execute collectives on a real mesh
suspend fun <T> ShardedSeries<T>.lower(mesh: DeviceMesh): Series<T> = series
suspend fun <T> ShardedCursor<T>.lower(mesh: DeviceMesh): Cursor<T> = cursor

// Cost model (from JAX Scaling Book roofline analysis)
data class CollectiveCost(
    val bytesSent: Long,
    val bytesReceived: Long,
    val latencyMs: Double,
    val bandwidthUtilization: Double,
) {
    fun totalBytes(): Long = bytesSent + bytesReceived
}

fun allGatherCost(sizeBytes: Long, devices: Int, bandwidthGBps: Double): CollectiveCost {
    val sent = sizeBytes * (devices - 1) / devices
    val recv = sizeBytes * (devices - 1) / devices
    val latency = 0.0 // Microseconds in real impl
    val bwUtil = (sent + recv) / (bandwidthGBps * 1e9)
    return CollectiveCost(sent, recv, latency, bwUtil)
}

fun reduceScatterCost(sizeBytes: Long, devices: Int, bandwidthGBps: Double): CollectiveCost {
    val sent = sizeBytes * (devices - 1) / devices
    val recv = sizeBytes / devices
    return CollectiveCost(sent, recv, 0.0, (sent + recv) / (bandwidthGBps * 1e9))
}

fun allReduceCost(sizeBytes: Long, devices: Int, bandwidthGBps: Double): CollectiveCost {
    val sent = sizeBytes * (devices - 1)
    val recv = sizeBytes * (devices - 1)
    return CollectiveCost(sent, recv, 0.0, (sent + recv) / (bandwidthGBps * 1e9))
}

fun allToAllCost(sizeBytes: Long, devices: Int, bandwidthGBps: Double): CollectiveCost {
    val sent = sizeBytes * (devices - 1) / devices
    val recv = sizeBytes * (devices - 1) / devices
    return CollectiveCost(sent, recv, 0.0, (sent + recv) / (bandwidthGBps * 1e9))
}