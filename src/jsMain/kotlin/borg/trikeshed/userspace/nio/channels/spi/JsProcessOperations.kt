package borg.trikeshed.userspace.nio.channels.spi

class JsProcessOperations : ProcessOperations {

    override suspend fun exec(
        command: String,
        args: List<String>,
        stdin: ByteArray?,
        env: Map<String, String>,
    ): ProcessResult {
        val childProcess: dynamic = js("require('child_process')")
        val options: dynamic = js("({})")
        val jsEnv: dynamic = js("Object.assign({}, process.env)")

        env.forEach { (key, value) ->
            jsEnv[key] = value
        }

        options.env = jsEnv
        options.encoding = "buffer"
        stdin?.let { options.input = it.decodeToString() }

        val result: dynamic = childProcess.spawnSync(command, args.toTypedArray(), options)
        val status = (result.status as Int?) ?: -1
        val stdout = ((result.stdout as String?) ?: "").encodeToByteArray()
        val stderr = ((result.stderr as String?) ?: "").encodeToByteArray()

        return ProcessResult(status, stdout, stderr)
    }
}
