package borg.trikeshed.couch.process

external interface ExecSyncOptions {
    var encoding: String
}

external interface ChildProcessModule {
    fun execSync(command: String, options: ExecSyncOptions = definedExternally): String
}

external fun require(module: String): ChildProcessModule
private val utf8Encoding: ExecSyncOptions = js("({encoding: 'utf8'})")

actual class ProcessShell {
    actual fun exec(command: String, args: List<String>): ProcessResult {
        val cmd = buildString {
            append(command)
            if (args.isNotEmpty()) append(" ")
            append(args.joinToString(" "))
        }

        return try {
            val childProcess = require("child_process")
            val out: String = childProcess.execSync(cmd, utf8Encoding)
            ProcessResult(0, out, "")
        } catch (e: Throwable) {
            ProcessResult(1, "", e.toString())
        }
    }

    actual fun exec(command: String, vararg args: String): ProcessResult = exec(command, args.toList())
}
