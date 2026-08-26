package borg.trikeshed.graal.subvm

import borg.trikeshed.btrfs.UserspaceBtrfs

/**
 * Minimalist POSIX tool emulations for polyglot guests — pure commonMain.
 *
 * The GraalPy/JS sleeve bans native modules, so the classic toolbox (cat, ls, grep, cp, mv, rm,
 * mkdir, echo, wc, head, tail, touch, pwd) is emulated in userspace over a [PosixFs] seam. The
 * canonical backing is a UserspaceBtrfs subvolume ([BtrfsPosixFs]); jvmMain registers each tool as
 * a host delegate on the GuestIsolate so guest code calls `host.call('cat', '/workspace/x')`.
 *
 * Every tool returns [ToolResult] (exit code + stdout + stderr) — POSIX shape, no exceptions
 * across the boundary. Paths are guest-absolute (`/workspace/...`): the leading `/` is stripped
 * into subvolume-relative form, `..` is rejected, never escaping the subvolume.
 */
interface PosixFs {
    fun readFile(path: String): ByteArray?
    fun writeFile(path: String, bytes: ByteArray): Boolean
    fun listDir(path: String): List<String>?
    fun isDir(path: String): Boolean
    fun isFile(path: String): Boolean
    fun mkdirs(path: String): Boolean
    fun delete(path: String): Boolean
    fun exists(path: String): Boolean
}

/** PosixFs over one UserspaceBtrfs subvolume — the local-user-filesystem LLM hosting seam. */
class BtrfsPosixFs(private val btrfs: UserspaceBtrfs, private val subvol: String) : PosixFs {
    override fun readFile(path: String): ByteArray? = btrfs.fetchFile(subvol, path)
    override fun writeFile(path: String, bytes: ByteArray): Boolean = btrfs.writeFile(subvol, path, bytes)
    override fun listDir(path: String): List<String>? = btrfs.listDirectory(subvol, path)
    override fun isDir(path: String): Boolean = btrfs.isDirectory(subvol, path)
    override fun isFile(path: String): Boolean = btrfs.isFile(subvol, path)
    override fun mkdirs(path: String): Boolean = btrfs.createDirectory(subvol, path)
    override fun delete(path: String): Boolean = btrfs.deleteFile(subvol, path)
    override fun exists(path: String): Boolean = btrfs.isFile(subvol, path) || btrfs.isDirectory(subvol, path)
}

data class ToolResult(val exit: Int, val stdout: String, val stderr: String) {
    companion object {
        fun ok(out: String = "") = ToolResult(0, out, "")
        fun err(exit: Int, msg: String) = ToolResult(exit, "", msg)
    }
}

object PosixToolbox {

    val tools = listOf("cat", "ls", "grep", "cp", "mv", "rm", "mkdir", "echo", "wc", "head", "tail", "touch", "pwd", "true", "false")

    /** Guest-absolute → subvolume-relative. Rejects `..` and empty results. Null = invalid. */
    fun normalize(guestPath: String): String? {
        val trimmed = guestPath.trim()
        if (trimmed.isEmpty()) return null
        val rel = trimmed.trimStart('/')
        if (rel.isEmpty()) return ""
        if (rel.split('/').any { it == ".." }) return null
        return rel.trimEnd('/').ifEmpty { "" }
    }

    fun run(fs: PosixFs, argv: List<String>): ToolResult {
        if (argv.isEmpty()) return ToolResult.err(2, "posix: empty command")
        return try {
            when (argv[0]) {
                "cat" -> cat(fs, argv.drop(1))
                "ls" -> ls(fs, argv.drop(1))
                "grep" -> grep(fs, argv.drop(1))
                "cp" -> cp(fs, argv.drop(1))
                "mv" -> mv(fs, argv.drop(1))
                "rm" -> rm(fs, argv.drop(1))
                "mkdir" -> mkdir(fs, argv.drop(1))
                "echo" -> ToolResult.ok(argv.drop(1).joinToString(" ") + "\n")
                "wc" -> wc(fs, argv.drop(1))
                "head" -> headOrTail(fs, argv.drop(1), fromHead = true)
                "tail" -> headOrTail(fs, argv.drop(1), fromHead = false)
                "touch" -> touch(fs, argv.drop(1))
                "pwd" -> ToolResult.ok("/\n")
                "true" -> ToolResult.ok()
                "false" -> ToolResult(1, "", "")
                else -> ToolResult.err(127, "posix: unknown tool: ${argv[0]}")
            }
        } catch (e: Exception) {
            ToolResult.err(2, "posix: ${argv[0]}: ${e.message}")
        }
    }

    private fun cat(fs: PosixFs, args: List<String>): ToolResult {
        if (args.isEmpty()) return ToolResult.err(1, "cat: missing operand")
        val out = StringBuilder(); var exit = 0; val err = StringBuilder()
        for (a in args) {
            val p = normalize(a) ?: return ToolResult.err(1, "cat: invalid path: $a")
            val bytes = if (p.isEmpty()) null else fs.readFile(p)
            if (bytes == null) { exit = 1; err.appendLine("cat: $a: No such file") }
            else out.append(bytes.decodeToString())
        }
        return ToolResult(exit, out.toString(), err.toString())
    }

    private fun ls(fs: PosixFs, args: List<String>): ToolResult {
        val target = args.firstOrNull() ?: "/"
        val p = normalize(target) ?: return ToolResult.err(1, "ls: invalid path: $target")
        if (p.isNotEmpty() && fs.isFile(p)) return ToolResult.ok(target.trimStart('/') + "\n")
        val entries = fs.listDir(p) ?: return ToolResult.err(1, "ls: $target: No such directory")
        return ToolResult.ok(entries.sorted().joinToString("\n").let { if (it.isEmpty()) "" else "$it\n" })
    }

    private fun grep(fs: PosixFs, args: List<String>): ToolResult {
        var ignoreCase = false
        val rest = args.dropWhile { if (it == "-i") { ignoreCase = true; true } else false }
        if (rest.size < 2) return ToolResult.err(2, "grep: usage: grep [-i] pattern file")
        val pattern = rest[0]
        val p = normalize(rest[1]) ?: return ToolResult.err(2, "grep: invalid path: ${rest[1]}")
        val bytes = fs.readFile(p) ?: return ToolResult.err(2, "grep: ${rest[1]}: No such file")
        val needle = if (ignoreCase) pattern.lowercase() else pattern
        val matches = bytes.decodeToString().lines().filter {
            val hay = if (ignoreCase) it.lowercase() else it
            needle in hay
        }
        return if (matches.isEmpty()) ToolResult(1, "", "") else ToolResult.ok(matches.joinToString("\n") + "\n")
    }

    private fun cp(fs: PosixFs, args: List<String>): ToolResult {
        if (args.size != 2) return ToolResult.err(1, "cp: usage: cp src dst")
        val src = normalize(args[0]) ?: return ToolResult.err(1, "cp: invalid path: ${args[0]}")
        val dst = normalize(args[1]) ?: return ToolResult.err(1, "cp: invalid path: ${args[1]}")
        val bytes = fs.readFile(src) ?: return ToolResult.err(1, "cp: ${args[0]}: No such file")
        return if (fs.writeFile(dst, bytes)) ToolResult.ok() else ToolResult.err(1, "cp: cannot write ${args[1]}")
    }

    private fun mv(fs: PosixFs, args: List<String>): ToolResult {
        val r = cp(fs, args)
        if (r.exit != 0) return r
        val src = normalize(args[0]) ?: return ToolResult.err(1, "mv: invalid path: ${args[0]}")
        return if (fs.delete(src)) ToolResult.ok() else ToolResult.err(1, "mv: cannot remove ${args[0]}")
    }

    private fun rm(fs: PosixFs, args: List<String>): ToolResult {
        val rest = args.filter { it != "-f" }
        if (rest.isEmpty()) return ToolResult.err(1, "rm: missing operand")
        val force = "-f" in args
        for (a in rest) {
            val p = normalize(a) ?: return ToolResult.err(1, "rm: invalid path: $a")
            if (p.isEmpty()) return ToolResult.err(1, "rm: refusing to remove /")
            if (fs.isDir(p)) return ToolResult.err(1, "rm: $a: is a directory (rm -r not emulated)")
            if (!fs.delete(p) && !force) return ToolResult.err(1, "rm: $a: No such file")
        }
        return ToolResult.ok()
    }

    private fun mkdir(fs: PosixFs, args: List<String>): ToolResult {
        if (args.isEmpty()) return ToolResult.err(1, "mkdir: missing operand")
        for (a in args) {
            val p = normalize(a) ?: return ToolResult.err(1, "mkdir: invalid path: $a")
            if (p.isEmpty()) return ToolResult.err(1, "mkdir: cannot create /")
            if (!fs.mkdirs(p)) return ToolResult.err(1, "mkdir: cannot create $a")
        }
        return ToolResult.ok()
    }

    private fun wc(fs: PosixFs, args: List<String>): ToolResult {
        if (args.size != 1) return ToolResult.err(1, "wc: usage: wc file")
        val p = normalize(args[0]) ?: return ToolResult.err(1, "wc: invalid path: ${args[0]}")
        val bytes = fs.readFile(p) ?: return ToolResult.err(1, "wc: ${args[0]}: No such file")
        val text = bytes.decodeToString()
        val lines = if (text.isEmpty()) 0 else text.lines().size - (if (text.endsWith("\n")) 1 else 0)
        val words = text.split(Regex("\\s+")).count { it.isNotEmpty() }
        return ToolResult.ok("${lines.pad(7)}${words.pad(7)}${bytes.size.pad(7)} ${args[0]}\n")
    }

    private fun headOrTail(fs: PosixFs, args: List<String>, fromHead: Boolean): ToolResult {
        var n = 10L
        val rest = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            if (args[i] == "-n" && i + 1 < args.size) { n = args[i + 1].toLongOrNull() ?: return ToolResult.err(1, "head: invalid -n"); i += 2 }
            else { rest += args[i]; i++ }
        }
        if (rest.size != 1) return ToolResult.err(1, "usage: ${if (fromHead) "head" else "tail"} [-n N] file")
        val p = normalize(rest[0]) ?: return ToolResult.err(1, "invalid path: ${rest[0]}")
        val bytes = fs.readFile(p) ?: return ToolResult.err(1, "${rest[0]}: No such file")
        var lines = bytes.decodeToString().lines()
        // a trailing newline yields one empty final element — POSIX head/tail ignore it
        if (lines.isNotEmpty() && lines.last().isEmpty()) lines = lines.dropLast(1)
        val picked = if (fromHead) lines.take(n.toInt()) else lines.takeLast(n.toInt())
        return ToolResult.ok(picked.joinToString("\n").let { if (it.isEmpty()) "" else "$it\n" })
    }

    private fun touch(fs: PosixFs, args: List<String>): ToolResult {
        if (args.isEmpty()) return ToolResult.err(1, "touch: missing operand")
        for (a in args) {
            val p = normalize(a) ?: return ToolResult.err(1, "touch: invalid path: $a")
            if (p.isEmpty()) return ToolResult.err(1, "touch: invalid path: $a")
            if (!fs.exists(p) && !fs.writeFile(p, ByteArray(0))) return ToolResult.err(1, "touch: cannot touch $a")
        }
        return ToolResult.ok()
    }

    private fun Int.pad(width: Int): String = toString().padStart(width)
}
