package borg.trikeshed.process

external interface ExecSyncOptions {
    var encoding: CharSequence
}

external interface ChildProcessModule {
    fun execSync(command: CharSequence, options: ExecSyncOptions = definedExternally): CharSequence
}

external fun require(module: CharSequence): ChildProcessModule
val utf8Encoding: ExecSyncOptions = js("({encoding: 'utf8'})")

actual class ProcessShell {
    actual fun exec(command: CharSequence, args: List<CharSequence>): ProcessResult {
        val cmd = buildString {
            append(command)
            if (args.isNotEmpty()) append(" ")
            append(args.joinToString(" "))
        }

        return try {
            val childProcess = require("child_process")
            val out: CharSequence = childProcess.execSync(cmd, utf8Encoding)
            ProcessResult(0, out, "")
        } catch (e: Throwable) {
            ProcessResult(1, "", e.toString())
        }
    }

    actual fun exec(command: CharSequence, vararg args: CharSequence): ProcessResult = exec(command, args.toList())
}
