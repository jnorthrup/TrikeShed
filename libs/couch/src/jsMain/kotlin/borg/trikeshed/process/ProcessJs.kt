package borg.trikeshed.process

actual class ProcessShell {
    actual fun exec(command: String, args: List<String>): ProcessResult {
        val cmd = buildString {
            append(command)
            if (args.isNotEmpty()) append(" ")
            append(args.joinToString(" "))
        }

        return try {
            val out: String = js("require('child_process').execSync(cmd, {encoding: 'utf8'})") as String
            ProcessResult(0, out, "")
        } catch (e: Throwable) {
            ProcessResult(1, "", e.toString())
        }
    }

    actual fun exec(command: String, vararg args: String): ProcessResult = exec(command, args.toList())
}
