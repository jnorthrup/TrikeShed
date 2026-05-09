package borg.trikeshed.userspace.nio.channels.spi

class JsProcessOperations : ProcessOperations {

    override suspend fun exec(
        command: String,
        args: List<String>,
        stdin: ByteArray?,
        env: Map<String, String>,
    ): ProcessResult {
        val cmd = buildString { append(command); for (a in args) { append(" "); append(a) } }
        val out: String = js("require('child_process').execSync(cmd, {encoding: 'utf8'})") as String
        return ProcessResult(0, out.encodeToByteArray(), byteArrayOf())
    }
}
