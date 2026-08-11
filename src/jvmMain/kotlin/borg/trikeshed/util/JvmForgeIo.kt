package borg.trikeshed.util

import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.TraitSpace
import borg.trikeshed.lib.j
import borg.trikeshed.util.io.ForgeCliArgs
import java.nio.file.Files as NioFiles
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlin.system.exitProcess

/**
 * Shared JVM-side helpers for the Forge entrypoints (daemon, litebike kanban
 * server, kanban http server). Centralizes the duplicates that used to live
 * at the bottom of every server file.
 */
object JvmForgeIo {

    /** Build a [TraitSpace] from a vararg of capabilities. */
    fun traitSpaceOf(vararg capabilities: Capability): TraitSpace = TraitSpace {
        capabilities.size j { index -> capabilities[index] }
    }

    /**
     * Atomically write [text] to [path], creating parent directories as
     * needed. Replaces the four identical copies that previously lived in
     * `KanbanHttpServerJvm`, `JvmKanbanServer`, `OroborosMain`, etc.
     */
    fun writeStringJvm(path: String, text: String) {
        val p = Paths.get(path)
        p.parent?.let { NioFiles.createDirectories(it) }
        NioFiles.writeString(
            p, text,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE,
        )
    }

    /**
     * Parsed `--port` / `--donor` / `-h, --help` config for the kanban
     * HTTP servers. Replaces the two byte-identical
     * `while (i.hasNext()) when (val a = i.next()) { ... }` blocks that
     * used to live at the top of both [KanbanServerMain] and
     * [JvmKanbanServer].
     *
     * Observability-equivalent to the prior hand-rolled loops: missing
     * `--port` value stays at 8888 (preserves the silent default),
     * missing `--donor` value stays null, `-h/--help` prints [usage] and
     * `exitProcess(2)`s. Unknown tokens fall through to the positional
     * tail (the prior `when` ignored them — same observable end state).
     */
    data class KanbanServerArgs(val port: Int = 8888, val donor: String? = null)

    fun parseKanbanServerArgs(
        args: Array<String>,
        programName: String,
        usage: String,
    ): KanbanServerArgs {
        var port = 8888
        var donor: String? = null
        val flags = listOf(
            ForgeCliArgs.Flag(name = "--port") { a, i ->
                val next = a.getOrNull(i + 1)
                next?.toIntOrNull()?.let { port = it }
                if (next == null) i + 1 else i + 2
            },
            ForgeCliArgs.Flag(name = "--donor") { a, i ->
                val next = a.getOrNull(i + 1)
                if (next != null) donor = next
                if (next == null) i + 1 else i + 2
            },
        )
        val remainingHelpAliases = setOf("-h", "--help")
        when (val r = ForgeCliArgs.parse(args.toList(), flags, remainingHelpAliases)) {
            is ForgeCliArgs.Result.Parsed -> {}
            ForgeCliArgs.Result.Help -> {
                System.err.println(usage)
                exitProcess(2)
            }
            is ForgeCliArgs.Result.Error -> {
                System.err.println("[$programName] ${r.message}")
                System.err.println(usage)
                exitProcess(2)
            }
        }
        return KanbanServerArgs(port, donor)
    }
}
