package borg.trikeshed.couch.process

actual class ProcessShell {
    actual fun exec(command: String, args: List<String>): ProcessResult {
        val cmd = buildString {
            append(command)
            if (args.isNotEmpty()) append(" ")
            append(args.joinToString(" "))
        }

        return try {
            // Use Node.js child_process.execSync when running under Node to execute commands.
            val out: String = js("require('child_process').execSync(cmd, {encoding: 'utf8'})") as String
            ProcessResult(0, out, "")
        } catch (e: Throwable) {
            // Non-zero exit: return a generic code and capture the error text. Using 1 to match common Unix 'false' exit code.
            ProcessResult(1, "", e.toString())
        }
    }

    actual fun exec(command: String, vararg args: String): ProcessResult = exec(command, args.toList())
}
