package borg.trikeshed.pi

import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.*
import java.nio.channels.FileChannel
import java.nio.file.attribute.BasicFileAttributeView

/** Host grant: existing, explicitly named TS/JS sources only. No directory listing or shell. */
class PiWorkspace(root: Path, val paths: Set<String>) {
    val root: Path = root.toRealPath()
    val snapshots = mutableMapOf<String, String>()
    init {
        require(paths.isNotEmpty() && paths.size <= 16)
        paths.forEach { path ->
            require(path.length <= 512 && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." && !it.startsWith(".") })
            require(!Path.of(path).isAbsolute && (path.endsWith(".ts") || path.endsWith(".js")))
        }
    }
    companion object {
        const val BYTES = 16_384
        fun configured(): PiWorkspace? {
            val root = System.getProperty("trikeshed.pi.workspace") ?: return null
            val files = System.getProperty("trikeshed.pi.files") ?: return null
            return PiWorkspace(Path.of(root), files.split(',').toSet())
        }
    }
    val definitions: List<Map<String, Any?>> get() = _l[
        definition("read", "Read granted UTF-8 TS/JS source. Granted paths: ${paths.joinToString(", ")}", _l["path"]),
        definition("edit", "Replace exactly one oldText occurrence in a granted source; fails on missing or ambiguous oldText. Read first.", _l["path", "oldText", "newText"])]
    fun definition(name: String, description: String, required: List<String>): Map<String, Any?> = _m[
        "name" j name, "description" j description, "parameters" j _m[
            "type" j "object", "properties" j required.associateWith { _m["type" j "string"] },
            "required" j required, "additionalProperties" j false]]

    @Synchronized
    fun dispatch(tool: String, args: Map<*, *>): String {
        val expected = if (tool == "read") _s["path"] else if (tool == "edit") _s["path", "oldText", "newText"] else error("unknown workspace tool")
        require(args.keys == expected && args.values.all { it is String }) { "invalid workspace arguments" }
        val path = args["path"] as String
        require(path in paths) { "path is not granted" }
        // SecureDirectoryStream pins each parent directory. NOFOLLOW prevents both directory and
        // leaf symlink substitution, including a concurrent rename outside the grant.
        val opened = mutableListOf<SecureDirectoryStream<Path>>()
        try {
            val base = Files.newDirectoryStream(root)
            val first = base as? SecureDirectoryStream<Path> ?: run { base.close(); error("secure directory access unavailable") }
            opened.add(first)
            var parent = first
            val parts = path.split('/')
            for (part in parts.dropLast(1)) {
                parent = parent.newDirectoryStream(Path.of(part), LinkOption.NOFOLLOW_LINKS)
                opened.add(parent)
            }
            val attributes = parent.getFileAttributeView(Path.of(parts.last()), BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS).readAttributes()
            require(attributes.isRegularFile && attributes.size() <= BYTES) { "bounded regular source required" }
            val options: Set<OpenOption> = if (tool == "edit") _s[StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS]
                else _s[StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS]
            parent.newByteChannel(Path.of(parts.last()), options).use { channel ->
                require(channel.size() <= BYTES) { "source exceeds byte budget" }
                val buffer = ByteBuffer.allocate(BYTES + 1)
                while (channel.read(buffer) > 0) { require(buffer.position() <= BYTES) { "source exceeds byte budget" } }
                buffer.flip()
                val before = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(buffer).toString()
                if (tool == "read") {
                    snapshots[path] = before
                    return jsonOf(_m["path" j path, "content" j before])
                }
                require(snapshots[path] == before) { "edit conflict: read current source before editing" }
                val old = args["oldText"] as String
                val replacement = args["newText"] as String
                val index = before.indexOf(old)
                require(old.isNotEmpty() && index >= 0 && before.indexOf(old, index + 1) < 0) { "edit conflict: oldText must occur exactly once" }
                val after = before.replaceRange(index, index + old.length, replacement)
                val bytes = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(after))
                require(bytes.remaining() <= BYTES) { "edited source exceeds byte budget" }
                channel.position(0)
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.truncate(channel.position())
                (channel as? FileChannel)?.force(true)
                snapshots[path] = after
                return jsonOf(_m["path" j path, "changed" j true, "diff" j "--- $path\n+++ $path\n-${old.replace("\n", "\n-")}\n+${replacement.replace("\n", "\n+")}"])
            }
        } finally { opened.asReversed().forEach { it.close() } }
    }
}
