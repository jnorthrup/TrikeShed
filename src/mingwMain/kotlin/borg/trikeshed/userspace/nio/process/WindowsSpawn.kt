@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.process

import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.windows.*

/** One argument quoted by the MSVCRT rules (CommandLineToArgvW): backslashes double only where a quote follows. */
fun windowsQuote(arg: String): String {
    if (arg.isNotEmpty() && arg.none { it == ' ' || it == '\t' || it == '"' }) return arg
    val out = StringBuilder("\"")
    var slashes = 0
    for (c in arg) when (c) {
        '\\' -> slashes++
        '"' -> { repeat(slashes * 2 + 1) { out.append('\\') }; out.append('"'); slashes = 0 }
        else -> { repeat(slashes) { out.append('\\') }; out.append(c); slashes = 0 }
    }
    repeat(slashes * 2) { out.append('\\') }
    return out.append('"').toString()
}

/** The parent's environment with [overrides] applied (names compare without case, as Windows does), as a block. */
fun windowsEnvironmentBlock(overrides: Map<String, String>): ByteArray {
    val entries = mutableListOf<ByteArray>()
    val block = GetEnvironmentStrings()
    if (block != null) try {
        var at = 0
        while (block[at] != 0.toByte()) {
            var end = at
            while (block[end] != 0.toByte()) end++
            entries.add(ByteArray(end - at) { block[at + it] })
            at = end + 1
        }
    } finally {
        FreeEnvironmentStringsA(block)
    }
    val replaced = overrides.keys.map { it.uppercase() }.toSet()
    val kept = entries.filter { entry ->
        val name = entry.decodeToString().substringBefore('=', "")
        name.isEmpty() || name.uppercase() !in replaced
    }
    val all = kept + overrides.map { (key, value) -> "$key=$value".encodeToByteArray() }
    val out = ByteArray(all.sumOf { it.size + 1 } + 1)
    var at = 0
    for (entry in all) { entry.copyInto(out, at); at += entry.size + 1 }
    return out
}

/**
 * CreateProcessA of [command] with [args]: stdin from [stdin] (or NUL), stdout and stderr to inherited temporary files
 * read back after exit, as the POSIX worker does. The first token resolves through PATH (".exe" appended). Past
 * [timeoutMs] (0 waits forever) the process is terminated and RuntimeException("process timed out after <n>ms") thrown.
 */
fun windowsSpawn(command: String, args: List<String>, env: Map<String, String>, stdin: ByteArray?, cwd: String?,
    timeoutMs: Long): ProcessResult = memScoped {
    val temps = mutableListOf<String>()
    val handles = mutableListOf<HANDLE>()
    val inherit = alloc<SECURITY_ATTRIBUTES>().apply {
        nLength = sizeOf<SECURITY_ATTRIBUTES>().convert(); lpSecurityDescriptor = null; bInheritHandle = TRUE
    }
    fun tempPath(): String {
        val dir = allocArray<ByteVar>(MAX_PATH + 1)
        check(GetTempPathA((MAX_PATH + 1).convert(), dir) != 0u) { "GetTempPathA failed: ${GetLastError()}" }
        val name = allocArray<ByteVar>(MAX_PATH + 1)
        check(GetTempFileNameA(dir.toKString(), "tsp", 0u, name) != 0u) { "GetTempFileNameA failed: ${GetLastError()}" }
        return name.toKString().also(temps::add)
    }
    fun open(path: String, write: Boolean): HANDLE {
        val h = CreateFileA(path, if (write) GENERIC_WRITE.convert() else GENERIC_READ.convert(),
            (FILE_SHARE_READ or FILE_SHARE_WRITE).convert(), inherit.ptr,
            (if (write) CREATE_ALWAYS else OPEN_EXISTING).convert(), FILE_ATTRIBUTE_NORMAL.convert(), null)
        check(h != null && h.rawValue != INVALID_HANDLE_VALUE?.rawValue) { "CreateFileA($path) failed: ${GetLastError()}" }
        return h!!.also(handles::add)
    }
    try {
        val input = if (stdin == null) open("NUL", write = false)
            else tempPath().let { borg.trikeshed.common.Files.write(it, stdin); open(it, write = false) }
        val outPath = tempPath()
        val errPath = tempPath()
        val output = open(outPath, write = true)
        val error = open(errPath, write = true)
        val startup = alloc<STARTUPINFOA>().apply {
            cb = sizeOf<STARTUPINFOA>().convert(); dwFlags = STARTF_USESTDHANDLES.convert()
            hStdInput = input; hStdOutput = output; hStdError = error
        }
        val process = alloc<PROCESS_INFORMATION>()
        val line = (listOf(command) + args).joinToString(" ") { windowsQuote(it) }
        val block = if (env.isEmpty()) null else windowsEnvironmentBlock(env)
        val created = (block ?: ByteArray(1)).usePinned { pinned ->
            CreateProcessA(null, line.cstr.ptr, null, null, TRUE, CREATE_NO_WINDOW.convert(),
                if (block == null) null else pinned.addressOf(0), cwd, startup.ptr, process.ptr)
        }
        check(created != 0) { "CreateProcessA($command) failed: ${GetLastError()}" }
        for (h in handles) CloseHandle(h)
        handles.clear()
        try {
            val waited = WaitForSingleObject(process.hProcess, if (timeoutMs == 0L) INFINITE else timeoutMs.convert())
            if (waited == WAIT_TIMEOUT.convert<UInt>()) {
                TerminateProcess(process.hProcess, 1u)
                WaitForSingleObject(process.hProcess, INFINITE)
                throw RuntimeException("process timed out after ${timeoutMs}ms")
            }
            val code = alloc<UIntVar>()
            check(GetExitCodeProcess(process.hProcess, code.ptr) != 0) { "GetExitCodeProcess failed: ${GetLastError()}" }
            ProcessResult(code.value.toInt(), borg.trikeshed.common.Files.readAllBytes(outPath),
                borg.trikeshed.common.Files.readAllBytes(errPath))
        } finally {
            CloseHandle(process.hThread)
            CloseHandle(process.hProcess)
        }
    } finally {
        for (h in handles) CloseHandle(h)
        for (path in temps) DeleteFileA(path)
    }
}

/** The capability's allow-list by basename (".exe" dropped), then [windowsSpawn] off the caller's thread. */
class MingwProcessWorker(val capability: ProcessCapability) : ProcessWorker {
    override suspend fun spawn(spec: ProcessSpec): ProcessResult {
        val basename = spec.command.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".exe")
        if (basename !in capability.allowedCommands) throw SecurityException("command '$basename' not in allowedCommands")
        val result = withContext(Dispatchers.IO) {
            windowsSpawn(spec.command, spec.args, spec.env, null, spec.cwd, spec.timeoutMs)
        }
        return ProcessResult(result.exitCode,
            result.stdout.copyOf(minOf(result.stdout.size, capability.maxStdoutBytes)),
            result.stderr.copyOf(minOf(result.stderr.size, capability.maxStderrBytes)))
    }
}
