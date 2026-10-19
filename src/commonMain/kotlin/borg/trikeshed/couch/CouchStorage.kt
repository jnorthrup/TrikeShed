package borg.trikeshed.couch

import borg.trikeshed.couch.isam.ConfixIsamCursorBridge
import borg.trikeshed.couch.persistence.*
import borg.trikeshed.context.*
import borg.trikeshed.isam.*
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.file.spi.*

enum class CouchStorageFormat(val id: String) {
    COMMIT("commit"), CONFIX_ISAM("confix-isam");

    companion object {
        fun parse(value: String?): CouchStorageFormat =
            if (value == null) COMMIT else entries.singleOrNull { it.id == value }
                ?: throw IllegalArgumentException("Unknown Couch storage format: $value")
    }
}

data class CouchConversion(val sourceRoot: ContentId, val destinationRoot: ContentId,
                           val revisions: Int, val localDocuments: Int)

/** One database and writer lifetime, shared by HTTP, attachments and dense inspection.
 * Selecting a format never converts, replaces or imports an existing database.
 * NONE requires caller-serialized access and close; durable writer leases and lower-level CAS/file locks remain.
 */
class CouchStorage private constructor(
    val couch: Couch,
    val format: CouchStorageFormat,
    val dense: ConfixIsamCursorBridge?,
    private val release: () -> Unit,
    val synchronization: Synchronization = Synchronization.MONITOR,
) : AutoCloseable {
    private val gate = synchronization.lock()
    private var closed = false

    override fun close(): Unit = synchronize(gate) {
        if (!closed) { closed = true; release() }
    }

    companion object {
        private const val CONVERSION = "conversion.cbor"
        private const val CONVERSION_FORMAT = "couch-conversion-v1"

        fun open(name: String, cas: CasStore, files: FileOperations, location: String,
                 format: CouchStorageFormat = CouchStorageFormat.COMMIT,
                 operations: IsamOperations? = null,
                 synchronization: Synchronization = Synchronization.MONITOR): CouchStorage {
            require(location.isNotBlank()) { "Couch storage location is empty" }
            if (cas.durability != StorageDurability.DURABLE) {
                require(format == CouchStorageFormat.COMMIT) { "Dense Couch requires durable CAS and filesystem storage" }
                if (files.durability != StorageDurability.DURABLE)
                    return CouchStorage(Couch(name, CouchStoreFactory.casBacked(cas, synchronization), cas), format, null,
                        {}, synchronization)
            }
            require(files.durability == StorageDurability.DURABLE) { "Durable Couch requires durable filesystem storage" }
            val directory = files.canonicalPath(location)
            // Both formats take the same selection lease before checking roots or opening a writer.
            files.mkdirs(directory)
            val lease = files.acquireExclusiveLease(files.resolvePath(directory, ".format.lock"))
            var backing: AutoCloseable? = null
            try {
                val conversionPath = files.resolvePath(directory, CONVERSION)
                if (files.exists(conversionPath)) {
                    val bytes = files.readAllBytes(conversionPath)
                    val receipt = CanonicalCbor.decodeMap(bytes)
                    require(CanonicalCbor.encodeMap(receipt).contentEquals(bytes) &&
                        receipt["format"] == CONVERSION_FORMAT && receipt["state"] == "complete") {
                        "Couch conversion is incomplete; destination cannot serve a database"
                    }
                    require(receipt.keys == setOf("format", "state", "sourceRoot", "destinationRoot", "revisions", "localDocuments")) {
                        "Invalid Couch conversion receipt"
                    }
                    ContentId(receipt["sourceRoot"] as? String ?: error("Missing conversion source root"))
                    ContentId(receipt["destinationRoot"] as? String ?: error("Missing conversion destination root"))
                    for (key in s_["revisions", "localDocuments"].view) {
                        require((receipt[key] as? Long)?.let { it in 0..Int.MAX_VALUE.toLong() } == true) {
                            "Invalid conversion $key"
                        }
                    }
                    // The receipt describes the initial converted root; later Couch writes may advance HEAD.
                    require(format == CouchStorageFormat.CONFIX_ISAM && files.exists(files.resolvePath("$directory.store", "HEAD"))) {
                        "Converted Couch requires its existing dense root"
                    }
                }
                val other = if (format == CouchStorageFormat.CONFIX_ISAM) directory else "$directory.store"
                require(!files.exists(files.resolvePath(other, "HEAD"))) {
                    "Existing Couch storage uses another format; explicit conversion is required"
                }
                if (cas.durability != StorageDurability.DURABLE) {
                    require(!files.exists(files.resolvePath(directory, "HEAD"))) {
                        "Existing durable Couch requires its durable CAS"
                    }
                    return CouchStorage(Couch(name, CouchStoreFactory.casBacked(cas, synchronization), cas), format, null,
                        { lease.close() }, synchronization)
                }
                val dense = if (format == CouchStorageFormat.CONFIX_ISAM)
                    ConfixIsamCursorBridge(directory, files, cas, operations, synchronization).also { backing = it } else null
                val couch = if (dense != null) dense.couch(name) else {
                    val commits = CouchCommitStore(cas, StorageDurability.DURABLE, files, directory,
                        synchronization = synchronization).also { backing = it }
                    Couch(name, CouchStoreFactory.durableCasBacked(cas, commits, synchronization), cas, commits.asLocalBacking())
                }
                return CouchStorage(couch, format, dense, {
                    try { backing?.close() } finally { lease.close() }
                }, synchronization)
            } catch (failure: Throwable) {
                try { backing?.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                try { lease.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }

        /**
         * Offline commit-to-dense conversion of the same database, using the same durable CAS.
         * Both source leases remain held through verification; the source HEAD is never changed.
         * Destination paths must be new. Failure retains an incomplete marker and partial artifacts
         * for inspection, never an automatically opened replacement or a destructive rollback.
         */
        fun convert(cas: CasStore, files: FileOperations, source: String, destination: String,
                    operations: IsamOperations? = null,
                    synchronization: Synchronization = Synchronization.MONITOR): CouchConversion {
            require(source.isNotBlank() && destination.isNotBlank()) { "Conversion paths must be explicit" }
            require(cas.durability == StorageDurability.DURABLE && files.durability == StorageDurability.DURABLE) {
                "Couch conversion requires durable CAS and filesystem storage"
            }
            val sourceDirectory = files.canonicalPath(source)
            val destinationDirectory = files.canonicalPath(destination)
            val sourceHead = files.resolvePath(sourceDirectory, "HEAD")
            require(files.exists(sourceHead) && !files.exists(files.resolvePath("$sourceDirectory.store", "HEAD"))) {
                "Conversion source must be an existing commit-format database"
            }
            require(!files.exists(destinationDirectory) && !files.exists("$destinationDirectory.store")) { "Conversion destination must be new" }
            return files.acquireExclusiveLease(files.resolvePath(sourceDirectory, ".format.lock")).use {
                require(files.exists(sourceHead) && !files.exists(files.resolvePath("$sourceDirectory.store", "HEAD")) &&
                    !files.exists(files.resolvePath(sourceDirectory, CONVERSION))) { "Conversion source changed before admission" }
                CouchCommitStore(cas, StorageDurability.DURABLE, files, sourceDirectory,
                    requireCompleteHistory = true, synchronization = synchronization).use { original ->
                    val root = files.readAllBytes(sourceHead)
                    val sourceRoot = ContentId.of(root)
                    files.mkdirs(destinationDirectory)
                    files.acquireExclusiveLease(files.resolvePath(destinationDirectory, ".format.lock")).use {
                        require(files.listDir(destinationDirectory).all { it == ".format.lock" } && !files.exists("$destinationDirectory.store")) {
                            "Conversion destination changed before admission"
                        }
                        val marker = files.resolvePath(destinationDirectory, CONVERSION)
                        fun publish(value: Map<String, Any?>) {
                            val bytes = CanonicalCbor.encodeMap(value)
                            files.writeAtomically(marker, bytes)
                            check(files.readAllBytes(marker).contentEquals(bytes)) { "Conversion receipt publication failed" }
                        }
                        val identity = mapOf("format" to CONVERSION_FORMAT, "sourceRoot" to sourceRoot.value)
                        publish(identity + ("state" to "pending"))
                        ConfixIsamCursorBridge(destinationDirectory, files, cas, operations, synchronization).use { it.importDatabase(original) }
                        ConfixIsamCursorBridge(destinationDirectory, files, cas, operations, synchronization).use { restored ->
                            restored.couch("conversion") // Recover the actual protocol projections, not only geometry descriptors.
                            val locals = restored.verifyDatabase(original)
                            check(root.contentEquals(files.readAllBytes(sourceHead))) { "Conversion source root changed" }
                            val result = CouchConversion(sourceRoot,
                                ContentId.of(files.readAllBytes(files.resolvePath("$destinationDirectory.store", "HEAD"))),
                                original.recoveredFrames.size, locals)
                            publish(identity + mapOf("state" to "complete", "destinationRoot" to result.destinationRoot.value,
                                "revisions" to result.revisions, "localDocuments" to result.localDocuments))
                            result
                        }
                    }
                }
            }
        }
    }
}
