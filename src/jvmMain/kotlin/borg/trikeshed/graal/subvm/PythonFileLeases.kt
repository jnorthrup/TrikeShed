package borg.trikeshed.graal.subvm

import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.file.spi.FileLease
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.vm.Teleported
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Host filesystem leases for virtual paths; descriptors never enter Python. */
class PythonFileLeases(
    val files: FileOperations,
    val directory: String,
    val publish: (String, ByteArray) -> Unit,
    val alive: () -> Boolean = { true },
) : AutoCloseable {
    companion object {
        val owners = ConcurrentHashMap<String, () -> Boolean>()
    }
    val owner = UUID.randomUUID().toString().also { owners[it] = alive }
    val leases = mutableMapOf<String, FileLease>()
    var sequence = 0L
    var closed = false

    @Synchronized
    fun call(arguments: List<Teleported>): Teleported {
        check(!closed) { "Python filesystem leases are closed" }
        val operation = (arguments.getOrNull(0) as? Teleported.Str)?.v
        val argument = (arguments.getOrNull(1) as? Teleported.Str)?.v
            ?: throw IllegalArgumentException("vfs_lease requires a path or lease id")
        return when (operation) {
            "owner" -> Teleported.Str(owner)
            "owner_alive" -> Teleported.Bool(owners[argument]?.invoke() == true)
            "acquire" -> {
                val path = Path.of(argument)
                require(path.isAbsolute && path.normalize().toString() == argument) { "canonical virtual path required" }
                files.mkdirs(directory)
                val lock = files.resolvePath(directory, ContentId.of(argument.encodeToByteArray()).hex + ".lock")
                val lease = files.acquireExclusiveLease(lock)
                val id = (++sequence).toString()
                leases[id] = lease
                Teleported.Str(id)
            }
            "release" -> {
                val lease = leases.remove(argument) ?: error("unknown filesystem lease")
                lease.close()
                Teleported.Bool(true)
            }
            "publish" -> {
                check(leases.isNotEmpty()) { "filesystem publication requires an active lease" }
                val path = Path.of(argument)
                require(path.isAbsolute && path.normalize().toString() == argument) { "canonical virtual path required" }
                val text = (arguments.getOrNull(2) as? Teleported.Str)?.v
                    ?: throw IllegalArgumentException("filesystem publication requires text")
                publish(argument, text.encodeToByteArray())
                Teleported.Bool(true)
            }
            else -> throw IllegalArgumentException("unknown vfs_lease operation")
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        var failure: Exception? = null
        for (lease in leases.values) try { lease.close() } catch (caught: Exception) {
            if (failure == null) failure = caught else failure.addSuppressed(caught)
        }
        leases.clear()
        owners.remove(owner)
        failure?.let { throw it }
    }
}
