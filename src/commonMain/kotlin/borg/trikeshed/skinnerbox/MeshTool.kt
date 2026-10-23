package borg.trikeshed.skinnerbox

import borg.trikeshed.ipns.Ed25519
import borg.trikeshed.ipns.IpnsKeyPair
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.file.StandardOpenOption
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.fileIoContext
import borg.trikeshed.util.oroboros.FileCasStore
import kotlinx.coroutines.*
import kotlin.random.Random
import kotlin.time.Clock

/** Configuration and launch commands; filesystem effects stay behind the common NIO provider. */
object MeshTool {
    const val HELP = """Skinnerbox peer mesh
  identity SEED_FILE
  plan DRAFT_JSON SEED_FILE OUTPUT_JSON
  serve CONFIG_JSON SEED_FILE STATE_DIRECTORY LISTEN_ADDRESS [--external-bind]
  snapshot CONFIG_JSON SEED_FILE SOURCE_STATE NAMESPACE MANIFEST_CBOR
  restore CONFIG_JSON OWNER_SEED_FILE SOURCE_STATE NAMESPACE MANIFEST_CBOR NEW_STATE

SEED_FILE contains exactly 32 private random bytes; identity prints only the public key and peer ID.
plan selects one priced N=3 cell per placement and saves immutable membership decisions.
serve loads those decisions without replanning. LISTEN_ADDRESS is a literal host:port.
Non-loopback HTTP binding requires --external-bind and a separately configured TLS reverse proxy.
Shutdown stops admission and drains accepted work. Three durable hosts are required to acknowledge a mutation.
snapshot and restore run offline under exclusive storage leases. Restore requires the original configured owner
and an absent destination directory; it does not elect a replacement or prove that later writes do not exist.
See docs/architecture/skinnerbox-configuration.md for the bounded configuration schema.
"""

    suspend fun run(scope: CoroutineScope, files: FileOperations, arguments: List<String>,
        stop: Deferred<Unit>, ready: (String) -> Unit = {}): String {
        if (arguments.isEmpty() || arguments[0] in setOf("help", "--help", "-h")) return HELP
        val command = arguments[0]
        require(command in setOf("identity", "plan", "serve", "snapshot", "restore")) { "Unknown mesh command\n$HELP" }
        require(when (command) { "identity" -> arguments.size == 2; "plan" -> arguments.size == 4
            "snapshot" -> arguments.size == 6; "restore" -> arguments.size == 7
            else -> arguments.size in 5..6 }) { HELP }
        if (command == "serve" && arguments.size == 6) require(arguments[5] == "--external-bind") { "Unknown serve option" }
        val seedPath = arguments[if (command == "identity") 1 else 2]
        val seed = read(seedPath, 32)
        try {
            require(seed.size == 32) { "Seed file must contain exactly 32 bytes" }
            val publicKey = Ed25519.publicKey(seed)
            val identity = IpnsKeyPair(publicKey, seed)
            if (command == "identity") return TwinJson.encode(mapOf(
                "publicKey" to publicKey.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') },
                "peerId" to ContentId.of(publicKey).hex)).decodeToString()
            val bytes = read(arguments[1], TwinJson.MAX_BYTES)
            if (command == "plan") {
                val planned = MeshConfiguration.plan(bytes, publicKey, Clock.System.now().toEpochMilliseconds())
                publish(files, arguments[3], planned.bytes())
                return "Saved pinned mesh configuration to ${arguments[3]}"
            }
            val configuration = MeshConfiguration.parse(bytes, publicKey)
            if (command == "snapshot" || command == "restore") return recover(scope, files, configuration, arguments)
            val runtime = MeshRuntime.open(scope, files, configuration, identity, arguments[3], arguments[4],
                allowExternalBind = arguments.size == 6)
            try {
                ready("Skinnerbox ${configuration.local.id} listening at ${runtime.listener.address}")
                stop.await()
            } finally { runtime.drain() }
            return "Skinnerbox drained"
        } finally { seed.fill(0) }
    }

    private suspend fun recover(scope: CoroutineScope, files: FileOperations, configuration: MeshConfiguration,
        arguments: List<String>): String {
        val command = arguments[0]
        val namespace = arguments[4]
        val cell = requireNotNull(configuration.cells.view.firstOrNull { it.namespace == namespace }) { "Recovery namespace has no configured cell" }
        require(cell.member(configuration.local.id) != null) { "Recovery reader must be a configured custodian" }
        if (command == "restore") require(cell.owner.id == configuration.local.id) { "Restore requires the original configured owner's seed" }
        val sourcePath = withContext(fileIoContext) {
            files.canonicalPath(arguments[3]).also { require(files.isDir(it)) { "Recovery source state directory is absent" } }
        }
        val snapshot = if (command == "restore") MeshSnapshot.decode(read(arguments[5], MeshSnapshot.MAX_BYTES), cell) else null
        return withStore(scope, files, configuration, sourcePath) { source ->
            if (command == "snapshot") {
                val exported = MeshRecovery.export(cell, source, configuration.local.id)
                publish(files, arguments[5], exported.bytes())
                "Saved snapshot ${exported.id.value} with ${exported.packets.size} events through sequence ${exported.cutoffSequence}"
            } else {
                val destinationPath = withContext(fileIoContext) {
                    files.canonicalPath(arguments[6]).also { path ->
                        require(path != sourcePath && !files.exists(path)) { "Restore destination must be a new separate directory" }
                        files.createDirectory(path)
                    }
                }
                withStore(scope, files, configuration, destinationPath) { destination ->
                    val restored = MeshRecovery.restore(requireNotNull(snapshot), cell, source, configuration.local.id,
                        destination, configuration.local.id)
                    "Restored ${restored.events} events through sequence ${restored.cutoffSequence} from ${restored.manifest.value}"
                }
            }
        }
    }

    private suspend fun <T> withStore(scope: CoroutineScope, files: FileOperations, configuration: MeshConfiguration,
        root: String, action: suspend (MeshPersistence) -> T): T {
        val store = MeshPersistence.open(scope, FileCasStore(files, files.resolvePath(root, "cas")),
            files, files.resolvePath(root, "db"), configuration.persistenceGrants)
        var failure: Throwable? = null
        try { return action(store) }
        catch (caught: Throwable) { failure = caught; throw caught }
        finally {
            val original = failure
            withContext(NonCancellable) {
                try { store.drain() }
                catch (cleanup: Throwable) { if (original == null) throw cleanup else if (original !== cleanup) original.addSuppressed(cleanup) }
            }
        }
    }

    private suspend fun read(path: String, limit: Int): ByteArray = withContext(fileIoContext) {
        // One descriptor and fixed allocation: replacement/growth cannot turn a stat into an unbounded read.
        val bytes = ByteArray(limit + 1)
        try {
            FileChannel.open(this, path, setOf(StandardOpenOption.READ)).use { channel ->
                val buffer = ByteBuffer(bytes)
                while (buffer.hasRemaining()) {
                    currentCoroutineContext().ensureActive()
                    val count = channel.read(buffer)
                    if (count < 0) break
                    check(count > 0) { "Mesh file read made no progress" }
                }
                require(buffer.position() <= limit) { "Mesh input exceeds its byte bound" }
                bytes.copyOf(buffer.position())
            }
        } finally { bytes.fill(0) }
    }

    private suspend fun publish(files: FileOperations, path: String, bytes: ByteArray) = withContext(fileIoContext) {
        require(!files.exists(path)) { "Destination configuration already exists" }
        val destination = files.canonicalPath(path)
        val temporary = "$destination.mesh-${Random.nextLong().toULong().toString(16)}"
        files.createDirectory(temporary)
        try {
            val payload = files.resolvePath(temporary, "payload")
            files.writeAtomically(payload, bytes)
            files.move(payload, destination)
        } finally { files.deleteRecursively(temporary) }
    }
}
