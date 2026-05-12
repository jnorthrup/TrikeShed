package borg.trikeshed.process

actual class ProcessShell {
    actual fun exec(command: CharSequence, args: List<CharSequence>): ProcessResult {
        val cmd = buildString {
            append(command)
            if (args.isNotEmpty()) append(" ")
            append(args.joinToString(" "))
        }

        return try {
            val out: CharSequence = js("require('child_process').execSync(cmd, {encoding: 'utf8'})") as CharSequence
            ProcessResult(0, out, "")
        } catch (e: Throwable) {
            ProcessResult(1, "", e.toString())
        }
    }

    actual fun exec(command: CharSequence, vararg args: CharSequence): ProcessResult = exec(command, args.toList())
}
