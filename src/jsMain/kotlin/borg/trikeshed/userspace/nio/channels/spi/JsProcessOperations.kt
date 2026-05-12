package borg.trikeshed.userspace.nio.channels.spi

class JsProcessOperations : ProcessOperations {

    override suspend fun exec(
        command: CharSequence,
        args: List<CharSequence>,
        stdin: ByteArray?,
        env: Map<CharSequence, CharSequence>,
    ): ProcessResult {
        val cmd = buildString { append(command); for (a in args) { append(" "); append(a) } }
        val out: CharSequence = js("require('child_process').execSync(cmd, {encoding: 'utf8'})") as CharSequence
        return ProcessResult(0, out.toString().encodeToByteArray(), byteArrayOf())
    }
}
