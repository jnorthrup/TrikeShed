package borg.trikeshed.graal.subvm

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
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
) : AutoCloseable {
    companion object {
        val owners = ConcurrentHashMap<String, Boolean>()
    }
    val leases = mutableMapOf<String, FileLease>()
    private val publications = mutableMapOf<String, String>()
    var sequence = 0L
    var closed = false
    // Admission can close while an admitted guest crossing is still using this
    // owner. The record lives until every retained lease has been closed.
    val owner = UUID.randomUUID().toString().also { owners[it] = true }

    @Synchronized
    fun call(arguments: List<Teleported>): Teleported {
        check(!closed) { "Python filesystem leases are closed" }
        val operation = (arguments.getOrNull(0) as? Teleported.Str)?.v
        val argument = (arguments.getOrNull(1) as? Teleported.Str)?.v
            ?: throw IllegalArgumentException("vfs_lease requires a path or lease id")
        return when (operation) {
            "owner" -> Teleported.Str(owner)
            "owner_alive" -> Teleported.Bool(owners[argument] == true)
            "acquire" -> {
                val path = Path.of(argument)
                require(path.isAbsolute && path.normalize().toString() == argument) { "canonical virtual path required" }
                val target = arguments.getOrNull(2)?.let {
                    (it as? Teleported.Str)?.v ?: throw IllegalArgumentException("publication path must be text")
                } ?: argument
                val targetPath = Path.of(target)
                require(targetPath.isAbsolute && targetPath.normalize().toString() == target) { "canonical publication path required" }
                files.mkdirs(directory)
                val held = mutableListOf<FileLease>()
                val lease = object : FileLease {
                    override fun close() {
                        var failure: Throwable? = null
                        val iterator = held.listIterator(held.size)
                        while (iterator.hasPrevious()) {
                            try { iterator.previous().close(); iterator.remove() }
                            catch (caught: Throwable) {
                                if (failure == null) failure = caught else if (failure !== caught) failure.addSuppressed(caught)
                            }
                        }
                        failure?.let { throw it }
                    }
                }
                try {
                    for (name in _l[argument, target].distinct().sorted()) {
                        val lock = files.resolvePath(directory, ContentId.of(name.encodeToByteArray()).hex + ".lock")
                        held.add(files.acquireExclusiveLease(lock))
                    }
                } catch (failure: Throwable) {
                    try { lease.close() } catch (closing: Throwable) {
                        // Keep partially released resources reachable for owner close and retry.
                        leases[(++sequence).toString()] = lease
                        if (closing !== failure) failure.addSuppressed(closing)
                    }
                    throw failure
                }
                val id = (++sequence).toString()
                leases[id] = lease
                publications[id] = target
                Teleported.Str(id)
            }
            "release" -> {
                val lease = leases[argument] ?: error("unknown filesystem lease")
                // A partially failed close must no longer authorize publication.
                publications.remove(argument)
                lease.close()
                leases.remove(argument)
                Teleported.Bool(true)
            }
            "publish" -> {
                val path = Path.of(argument)
                require(path.isAbsolute && path.normalize().toString() == argument) { "canonical virtual path required" }
                check(publications.values.any { it == argument }) { "filesystem publication requires a lease for this path" }
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
        closed = true
        var failure: Throwable? = null
        val iterator = leases.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val id = entry.key
            try {
                entry.value.close()
                iterator.remove()
                publications.remove(id)
            } catch (caught: Throwable) {
                if (failure == null) failure = caught else if (failure !== caught) failure.addSuppressed(caught)
            }
        }
        if (leases.isEmpty()) owners.remove(owner)
        failure?.let { throw it }
    }
}
