package borg.trikeshed.agent

import borg.trikeshed.lcnc.AgentCliInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The roster of coding-agent CLIs on this host: which binaries exist, how each is run
 * non-interactively, and how its answer is read back. Probed once at boot with a
 * bounded `--version`; `which` is never trusted here because on the owner's Mac it
 * resolves to cmux shims, so the probe walks real directories and skips shim dirs.
 *
 * Enabled by default: codex and opencode. claude is installed but stays disabled
 * unless the operator says `--agents codex,opencode,claude` (RFC 0001 section 7's
 * quota spirit; its keychain reads misbehave under launchd).
 */
class AgentCli(
    val id: String,
    val binary: String,
    /** How the brief reaches the CLI: on stdin (codex, claude) or as the last argument (opencode). */
    val briefOnStdin: Boolean,
    private val argv: (scratch: File, model: String, lastMessage: File, brief: String) -> List<String>,
) {
    fun command(scratch: File, model: String, lastMessage: File, brief: String): List<String> = argv(scratch, model, lastMessage, brief)

    companion object {
        const val CODEX = "codex"
        const val OPENCODE = "opencode"
        const val CLAUDE = "claude"
        val DEFAULT_ENABLED: Set<String> = setOf(CODEX, OPENCODE)
        val KNOWN: List<String> = listOf(CODEX, OPENCODE, CLAUDE)

        /** Directories a real install lands in, in order; then the PATH walk minus shim and temp dirs. */
        fun candidateDirs(home: String = System.getProperty("user.home") ?: "", path: String = System.getenv("PATH") ?: "", tmp: String = System.getenv("TMPDIR") ?: ""): List<String> {
            val fixed = listOf("/opt/homebrew/bin", "$home/.local/bin", "/usr/local/bin")
            val walked = path.split(File.pathSeparatorChar).filter { it.isNotBlank() }
                .filter { !it.contains("cmux-cli-shims") && (tmp.isBlank() || !it.startsWith(tmp.trimEnd('/'))) }
            return (fixed + walked).distinct()
        }

        fun definition(id: String, binary: String): AgentCli = when (id) {
            CODEX -> AgentCli(id, binary, briefOnStdin = true) { scratch, model, last, _ ->
                buildList {
                    add(binary); add("exec"); add("--json"); add("-C"); add(scratch.absolutePath)
                    add("-s"); add("workspace-write"); add("--ephemeral"); add("--skip-git-repo-check")
                    add("-o"); add(last.absolutePath)
                    if (model.isNotBlank()) { add("-m"); add(model) }
                    add("-")
                }
            }
            OPENCODE -> AgentCli(id, binary, briefOnStdin = false) { scratch, model, _, brief ->
                buildList {
                    add(binary); add("run"); add("--format"); add("json"); add("--dir"); add(scratch.absolutePath); add("--auto")
                    if (model.isNotBlank()) { add("-m"); add(model) }
                    add(brief)
                }
            }
            CLAUDE -> AgentCli(id, binary, briefOnStdin = true) { _, model, _, _ ->
                buildList {
                    add(binary); add("-p"); add("--output-format"); add("json"); add("--permission-mode"); add("acceptEdits"); add("--no-session-persistence")
                    if (model.isNotBlank()) { add("--model"); add(model) }
                }
            }
            else -> throw IllegalArgumentException("unknown agent id '$id'")
        }

        /** A resolved roster: every known id, found or not, with the reason it is not offered. */
        suspend fun probe(enabled: Set<String>, dirs: List<String> = candidateDirs(), versionTimeoutMs: Long = 5_000L): List<AgentCliInfo> = withContext(Dispatchers.IO) {
            KNOWN.map { id ->
                val found = dirs.asSequence().map { File(it, id) }.firstOrNull { it.isFile && it.canExecute() }
                when {
                    found == null -> AgentCliInfo(id, "", "", enabled = false, why = "not installed in ${dirs.take(3).joinToString(", ")} or on PATH")
                    id !in enabled -> AgentCliInfo(id, found.absolutePath, version(found, versionTimeoutMs), enabled = false, why = "installed; enable with --agents")
                    else -> {
                        val v = version(found, versionTimeoutMs)
                        if (v.isEmpty()) AgentCliInfo(id, found.absolutePath, "", enabled = false, why = "--version gave nothing within ${versionTimeoutMs} ms")
                        else AgentCliInfo(id, found.absolutePath, v, enabled = true)
                    }
                }
            }
        }

        /** `<binary> --version`, bounded, first line only; empty on any failure. Blocking, so IO-dispatched by the caller. */
        fun version(binary: File, timeoutMs: Long): String = runCatching {
            val p = ProcessBuilder(binary.absolutePath, "--version").redirectErrorStream(true).apply { environment().clear(); environment().putAll(AgentEnvironment.build(System.getenv(), listOf(binary.parent))) }.start()
            val out = StringBuilder()
            val reader = Thread { runCatching { p.inputStream.bufferedReader().use { r -> r.lineSequence().take(4).forEach { out.append(it).append('\n') } } } }
            reader.isDaemon = true; reader.start()
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) { p.destroyForcibly(); p.waitFor(1, TimeUnit.SECONDS) }
            reader.join(500)
            out.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty().take(120)
        }.getOrDefault("")
    }
}
