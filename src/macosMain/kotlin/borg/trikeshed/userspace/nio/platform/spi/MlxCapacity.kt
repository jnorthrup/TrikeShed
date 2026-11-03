package borg.trikeshed.userspace.nio.platform.spi

import kotlinx.cinterop.*
import platform.posix.*

/** Process-wide MLX settings and the exact file bytes retained by its verified readers. */
internal data class MlxLimits(val memory: Long, val cache: Long, val wired: Long, val checkpoints: Long)

internal object MlxCapacity {
    private val mutex = nativeHeap.alloc<pthread_mutex_t>().also {
        check(pthread_mutex_init(it.ptr, null) == 0) { "MLX capacity mutex initialization failed" }
    }
    private val owners = mutableMapOf<Any, Long>()
    private var limits: MlxLimits? = null

    private inline fun <T> locked(block: () -> T): T {
        check(pthread_mutex_lock(mutex.ptr) == 0) { "MLX capacity mutex lock failed" }
        try { return block() } finally { pthread_mutex_unlock(mutex.ptr) }
    }

    fun open(owner: Any, requested: MlxLimits, configure: () -> Unit) = locked {
        check(owner !in owners) { "MLX session already registered" }
        check(limits == null || limits == requested) { "MLX process-wide limits differ from live sessions" }
        if (limits == null) { configure(); limits = requested }
        owners[owner] = 0L
    }

    fun reserve(owner: Any, bytes: Long) = locked {
        val current = checkNotNull(owners[owner]) { "MLX session is not registered" }
        val limit = checkNotNull(limits).checkpoints
        val used = owners.values.sum()
        require(bytes >= 0 && bytes <= limit - used) { "MLX checkpoint byte budget exceeded" }
        owners[owner] = current + bytes
    }

    fun release(owner: Any, bytes: Long) = locked {
        val current = checkNotNull(owners[owner]) { "MLX session is not registered" }
        check(bytes >= 0 && bytes <= current) { "Invalid MLX checkpoint byte release" }
        owners[owner] = current - bytes
    }

    fun close(owner: Any) = locked {
        check(owners[owner] == 0L) { "MLX session still owns checkpoint bytes" }
        owners.remove(owner)
        if (owners.isEmpty()) limits = null
    }

    fun snapshot(): MlxMatrixOperations.CheckpointUsage = locked {
        MlxMatrixOperations.CheckpointUsage(owners.values.sum(), limits?.checkpoints, owners.size)
    }
}
