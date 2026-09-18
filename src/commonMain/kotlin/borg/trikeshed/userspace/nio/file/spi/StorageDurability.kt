package borg.trikeshed.userspace.nio.file.spi

/** Whether acknowledged storage publication survives a process restart. */
enum class StorageDurability { VOLATILE, DURABLE }

/** Owns an exclusive filesystem lease until closed; closing is idempotent. */
fun interface FileLease : AutoCloseable {
    override fun close()
}
