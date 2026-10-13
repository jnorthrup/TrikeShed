package borg.trikeshed.graal.subvm

import borg.trikeshed.vm.*
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Verified pure wheel bytes, later copied into the guest's Btrfs workspace, never host sys.path. */
data class PythonWheels(
    val manifest: GuestModuleManifest,
    val sources: Map<String, Pair<String, String>>,
    val resources: Map<String, ByteArray>,
) {
    companion object {
        const val MODULE = "hermes-python"
        const val MAX_WHEEL_BYTES = 32 * 1024 * 1024
        const val MAX_FILE_BYTES = 8 * 1024 * 1024
        const val MAX_EXPANDED_BYTES = 64 * 1024 * 1024
        const val MAX_ENTRIES = 10_000

        fun installed(): PythonWheels? = GuestModules.moduleDir(MODULE)?.toPath()?.let(::read)

        fun read(directory: Path): PythonWheels {
            val manifest = GuestModuleManifest.parse(Files.readString(directory.resolve(GuestModuleLayout.MANIFEST)))
            require(manifest.module == MODULE && manifest.parent == null) { "invalid Python guest module manifest" }
            require(manifest.entries.map { it.file }.distinct().size == manifest.entries.size) { "duplicate wheel manifest rows" }
            val archives = sortedMapOf<String, ByteArray>()
            var archivedBytes = 0L
            Files.list(directory.resolve(GuestModuleLayout.LIB)).use { paths ->
                paths.forEach { path ->
                    val name = path.fileName.toString()
                    require(Files.isRegularFile(path) && name.endsWith("-py3-none-any.whl")) { "not a pure Python wheel: $name" }
                    val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_WHEEL_BYTES + 1) }
                    archivedBytes += bytes.size
                    require(bytes.size <= MAX_WHEEL_BYTES && archivedBytes <= MAX_EXPANDED_BYTES && archives.size < MAX_ENTRIES) { "wheel exceeds byte bound: $name" }
                    archives[name] = bytes
                }
            }
            // Verify precisely the bytes that will be decoded; a concurrent replacement cannot change the mount.
            val verification = verifyGuestModule(manifest, archives.map { (name, bytes) ->
                GuestModuleEntry(name, bytes.size.toLong(), MessageDigest.getInstance("SHA-256")
                    .digest(bytes).joinToString("") { "%02x".format(it) })
            })
            require(verification.ok) { "Python wheel verification failed: ${verification.problems.joinToString()}" }
            val sources = linkedMapOf<String, Pair<String, String>>()
            val resources = linkedMapOf<String, ByteArray>()
            var expanded = 0L
            var count = 0
            for ((wheel, bytes) in archives) {
                var wheelMetadata = false
                ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        require(++count <= MAX_ENTRIES) { "too many wheel entries" }
                        val path = entry.name.removeSuffix("/")
                        val cells = path.split('/')
                        require(cells.all { it.isNotEmpty() && it != "." && it != ".." } &&
                            path.none { it == '\\' || it == ':' || it == '\u0000' }) { "invalid wheel path: $path" }
                        if (entry.isDirectory) continue
                        require(!path.endsWith(".so") && !path.endsWith(".pyd") && !path.endsWith(".dll") &&
                            !path.endsWith(".dylib") && !path.endsWith(".pyc") && !path.endsWith(".pth")) {
                            "executable native or path artifact in pure wheel: $path"
                        }
                        require(cells.none { it.endsWith(".data") }) { "wheel relocation is not supported: $path" }
                        val content = zip.readNBytes(MAX_FILE_BYTES + 1)
                        expanded += content.size
                        require(content.size <= MAX_FILE_BYTES && expanded <= MAX_EXPANDED_BYTES) { "expanded wheel exceeds byte bound" }
                        if (path.endsWith(".dist-info/WHEEL")) {
                            val metadata = content.decodeToString(throwOnInvalidSequence = true).lineSequence().map { it.trimEnd('\r') }.toList()
                            require("Root-Is-Purelib: true" in metadata && "Tag: py3-none-any" in metadata) { "wheel is not pure Python: $wheel" }
                            wheelMetadata = true
                        }
                        require(resources.putIfAbsent(path, content) == null) { "duplicate wheel resource: $path" }
                        if (path.endsWith(".py")) {
                            val names = cells.dropLast(1) + cells.last().removeSuffix(".py")
                            val module = (if (names.last() == "__init__") names.dropLast(1) else names)
                            require(module.isNotEmpty() && module.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }) { "invalid Python module path: $path" }
                            require(sources.putIfAbsent(module.joinToString("."), "wheel:$wheel/$path" to
                                content.decodeToString(throwOnInvalidSequence = true)) == null) { "duplicate wheel module: $path" }
                        }
                    }
                }
                require(wheelMetadata) { "missing WHEEL metadata: $wheel" }
            }
            return PythonWheels(manifest, sources, resources)
        }
    }
}
