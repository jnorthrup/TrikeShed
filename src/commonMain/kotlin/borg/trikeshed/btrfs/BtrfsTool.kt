package borg.trikeshed.btrfs

import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.channels.UringChannels
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.fileIoContext
import borg.trikeshed.userspace.nio.spi.currentNioCapabilityReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Operator commands over the same common filesystem element used by host mounts. */
object BtrfsTool {
    const val HELP = """Userspace Btrfs
  create STATE MEMBER_MIB [PROFILE=SPAN] [MEMBERS=1] [CODEC=RAW]
  info STATE
  ls STATE [PATH=/]
  mkdir STATE PATH
  put STATE HOST_FILE PATH
  get STATE PATH HOST_FILE
  rm STATE PATH
  mv STATE SOURCE DESTINATION
  truncate STATE PATH BYTES
  add STATE MEMBER_MIB
  balance STATE PROFILE [MEMBERS] [MEMBER_MIB]
  compression STATE RAW|ZSTD|LZ4
  export STATE NEW_IMAGE
  mount STATE EMPTY_DIRECTORY [MACFUSE_LIBRARY]
  control 127.0.0.1:PORT TOKEN info|add|balance|compression [OPERANDS]

STATE owns a generation manifest and userspace RAID member files.
export produces a standalone native Btrfs image for offline btrfs-progs.
Mounting requires a host transport; macOS arm64 uses macFUSE and JDK 25.
Filesystem mutations publish complete new generations. Retain old generations
until their recovery value is no longer needed. Native Linux ioctls are not provided.
"""

    suspend fun run(
        scope: CoroutineScope,
        files: FileOperations,
        arguments: List<String>,
        mount: (suspend (BtrfsFileSystemElement, String, String?) -> Unit)? = null,
    ): String {
        if (arguments.isEmpty() || arguments[0] in setOf("help", "--help", "-h")) return HELP
        val command = arguments[0]
        require(command in setOf("create", "info", "ls", "mkdir", "put", "get", "rm", "mv", "truncate",
            "add", "balance", "compression", "export", "mount")) { "Unknown command: $command\n$HELP" }
        val state = arguments.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("A state directory is required\n$HELP")
        val args = arguments.drop(2)
        val root = withContext(fileIoContext) { files.canonicalPath(state) }
        val manifest = files.resolvePath(root, "volume.manifest")
        if (command == "create") {
            require(args.size in 1..4) { "create STATE MEMBER_MIB [PROFILE] [MEMBERS] [CODEC]" }
            val blocks = blocks(args[0])
            val layout = args.getOrNull(1)?.let { BtrfsVolumeLayout.valueOf(it.uppercase()) } ?: BtrfsVolumeLayout.SPAN
            val count = args.getOrNull(2)?.toInt() ?: 1
            require(count in 1..256) { "Member count must be between 1 and 256" }
            val codec = args.getOrNull(3)?.let { BtrfsMetadataCompression.valueOf(it.uppercase()) }
                ?: BtrfsMetadataCompression.RAW
            val members = count j { index: Int ->
                BtrfsRaidImageMember((index + 1).toULong(), files.resolvePath(root, "device-$index.img"), blocks)
            }
            val config = BtrfsRaidImageConfig(layout, BLOCK_SIZE, STRIPE_BLOCKS, members)
            require(config.validate().capacity in (BtrfsFilesystemImage.MINIMUM_BYTES / BLOCK_SIZE)..
                (BtrfsFilesystemImage.MAXIMUM_BYTES / BLOCK_SIZE)) {
                "The logical Btrfs image needs 128 MiB through 256 GiB"
            }
            withContext(fileIoContext) {
                require(!files.exists(manifest)) { "A volume already exists at $root" }
                files.mkdirs(root)
            }
            val backing = BtrfsRaidRebalance.create(scope, files, manifest, config, codec) { volume ->
                BtrfsFilesystemImage.write(volume, emptyList())
            }
            val filesystem = BtrfsFileSystemElement.mount(scope, backing)
            return owned(filesystem) { describe(it) }
        }

        val backing = BtrfsRaidRebalance.open(scope, files, manifest)
        val filesystem = BtrfsFileSystemElement.mount(scope, backing)
        return owned(filesystem) { fs ->
            runMounted(scope, files, root, fs, listOf(command) + args, mount)
        }
    }

    /** Execute against the existing mount owner, including live topology changes. */
    suspend fun runMounted(
        scope: CoroutineScope,
        files: FileOperations,
        root: String,
        fs: BtrfsFileSystemElement,
        arguments: List<String>,
        mount: (suspend (BtrfsFileSystemElement, String, String?) -> Unit)? = null,
    ): String {
        val command = arguments.firstOrNull() ?: throw IllegalArgumentException("Missing command")
        val args = arguments.drop(1)
        return when (command) {
                "info" -> { require(args.isEmpty()); describe(fs) }
                "ls" -> { require(args.size <= 1); fs.list(args.firstOrNull() ?: "/").joinToString("\n") }
                "mkdir" -> { arity(args, 1); fs.mkdir(args[0], recursive = true); "Created ${args[0]}" }
                "put" -> {
                    arity(args, 2)
                    val bytes = withContext(fileIoContext) {
                        val fd = files.open(args[0], readOnly = true)
                        require(fd >= 0) { "Cannot open input: ${args[0]}" }
                        try {
                            require(files.size(fd) in 0..BtrfsFilesystemImage.MAX_SNAPSHOT_DATA_BYTES) {
                                "Input exceeds the 256 MiB snapshot file-data limit"
                            }
                        } finally { files.close(fd) }
                        files.readAllBytes(args[0])
                    }
                    fs.write(args[1], bytes)
                    "Wrote ${bytes.size} bytes to ${args[1]}"
                }
                "get" -> {
                    arity(args, 2)
                    val bytes = fs.read(args[0])
                    withContext(fileIoContext) {
                        require(!files.exists(args[1])) { "Destination already exists: ${args[1]}" }
                        val destination = files.canonicalPath(args[1])
                        val temporary = "$destination.btrfs-download-${Random.nextLong().toULong().toString(16)}"
                        files.createDirectory(temporary)
                        try {
                            val payload = files.resolvePath(temporary, "payload")
                            files.writeAtomically(payload, bytes)
                            // move has no-replace semantics, including a racing creator.
                            files.move(payload, destination)
                        } finally { files.deleteRecursively(temporary) }
                    }
                    "Wrote ${bytes.size} bytes to ${args[1]}"
                }
                "rm" -> { arity(args, 1); fs.remove(args[0]); "Removed ${args[0]}" }
                "mv" -> { arity(args, 2); fs.rename(args[0], args[1]); "Moved ${args[0]} to ${args[1]}" }
                "truncate" -> { arity(args, 2); fs.truncate(args[0], args[1].toLong()); "Resized ${args[0]}" }
                "compression" -> {
                    arity(args, 1)
                    fs.setMetadataCompression(BtrfsMetadataCompression.valueOf(args[0].uppercase()))
                    describe(fs)
                }
                "add", "balance" -> {
                    val old = fs.config()
                    val adding = command == "add"
                    if (adding) arity(args, 1) else require(args.size in 1..3) {
                        "balance STATE PROFILE [MEMBERS] [MEMBER_MIB]"
                    }
                    val layout = if (adding) {
                        if (old.layout == BtrfsVolumeLayout.SINGLE) BtrfsVolumeLayout.SPAN else old.layout
                    } else BtrfsVolumeLayout.valueOf(args[0].uppercase())
                    val count = if (adding) old.members.size + 1 else args.getOrNull(1)?.toInt() ?: old.members.size
                    require(count in 1..256) { "Member count must be between 1 and 256" }
                    val specified = if (adding) blocks(args[0]) else args.getOrNull(2)?.let(::blocks)
                    val capacity = Array(count) { index ->
                        when {
                            adding && index < old.members.size -> old.members[index].capacityBlocks
                            specified != null -> specified
                            index < old.members.size -> old.members[index].capacityBlocks
                            else -> old.members[old.members.size - 1].capacityBlocks
                        }
                    }
                    val proposed = BtrfsRaidImageConfig(layout, old.blockSize, old.stripeBlocks,
                        count j { index: Int -> BtrfsRaidImageMember((index + 1).toULong(), "validate-$index", capacity[index]) })
                    require(proposed.validate().capacity in old.validate().capacity..
                        (BtrfsFilesystemImage.MAXIMUM_BYTES / old.blockSize)) {
                        "Relocation must preserve capacity and cannot exceed 256 GiB"
                    }
                    val generation = fs.generation() + 1
                    val directory = withContext(fileIoContext) {
                        var attempt = 0
                        var candidate = files.resolvePath(root, "generation-$generation-$attempt")
                        while (files.exists(candidate)) {
                            require(attempt < 10000) { "Too many retained generation attempts" }
                            candidate = files.resolvePath(root, "generation-$generation-${++attempt}")
                        }
                        files.createDirectory(candidate)
                        candidate
                    }
                    val members = count j { index: Int ->
                        BtrfsRaidImageMember((index + 1).toULong(), files.resolvePath(directory, "device-$index.img"), capacity[index])
                    }
                    fs.migrate(BtrfsRaidImageConfig(layout, old.blockSize, old.stripeBlocks, members))
                    describe(fs)
                }
                "export" -> {
                    arity(args, 1)
                    val config = fs.config()
                    val channel = UringChannels.open(scope, entries = 8)
                    try {
                        val image = BtrfsUringFileVolume.open(channel, args[0], config.blockSize,
                            config.validate().capacity, backendReport = currentNioCapabilityReport())
                        try { fs.export(image) } finally { withContext(NonCancellable) { image.drain() } }
                    } finally { withContext(NonCancellable) { channel.drain() } }
                    "Exported native Btrfs image to ${args[0]}"
                }
                "mount" -> {
                    require(args.size in 1..2) { "mount STATE EMPTY_DIRECTORY [MACFUSE_LIBRARY]" }
                    val transport = requireNotNull(mount) { "No host mount transport is installed on this platform" }
                    transport(fs, args[0], args.getOrNull(1))
                    "Unmounted ${args[0]}"
                }
                else -> throw IllegalArgumentException("Unknown mounted command: $command")
            }
    }

    private suspend fun describe(fs: BtrfsFileSystemElement): String {
        val config = fs.config()
        return "generation=${fs.generation()} profile=${config.layout} members=${config.members.size} " +
            "bytes=${config.validate().capacity * config.blockSize} metadata=${fs.metadataCompression()}"
    }

    private suspend fun <T> owned(fs: BtrfsFileSystemElement, body: suspend (BtrfsFileSystemElement) -> T): T {
        var failure: Throwable? = null
        try { return body(fs) } catch (caught: Throwable) { failure = caught; throw caught }
        finally {
            withContext(NonCancellable) {
                try { fs.drain() } catch (cleanup: Throwable) {
                    if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                }
            }
        }
    }

    private fun arity(args: List<String>, count: Int) = require(args.size == count) { "Expected $count operands; see help" }
    private fun blocks(value: String): Long {
        val mib = value.toLong()
        require(mib in 1..(Long.MAX_VALUE / 1024 / 1024)) { "Member size must be a positive MiB count" }
        return mib * (1024 * 1024 / BLOCK_SIZE)
    }
    private const val BLOCK_SIZE = 4096
    private const val STRIPE_BLOCKS = 16
}
