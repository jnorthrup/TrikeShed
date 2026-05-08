package borg.trikeshed.userspace.nio.channels.spi

class JsProcessOperations : ProcessOperations {

    override fun exec(command: String, vararg args: String): ExecResult {
        val cmd = buildString { append(command); for (a in args) { append(" "); append(a) } }
        val out: String = js("require('child_process').execSync(cmd, {encoding: 'utf8'})")
        return ExecResult(0, out.trimEnd(), "")
    }
}
