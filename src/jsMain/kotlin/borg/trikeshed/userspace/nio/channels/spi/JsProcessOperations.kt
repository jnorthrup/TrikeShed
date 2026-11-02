package borg.trikeshed.userspace.nio.channels.spi

class JsProcessOperations : ProcessOperations {

    override suspend fun exec(
        command: String,
        args: List<String>,
        stdin: ByteArray?,
        env: Map<String, String>,
    ): ProcessResult {
        // The browser bundles share this provider with Node, but cannot start processes.
        // Check before touching Node globals or the browser's empty webpack fallback.
        val node: Boolean = js("typeof process !== 'undefined' && process.versions != null && process.versions.node != null") as Boolean
        if (!node) return ProcessResult(-1, byteArrayOf(), "exec not available in browser".encodeToByteArray())
        val childProcess: dynamic = js("require('child_process')")
        // An embedded browser may expose Node's process object even though the
        // browser bundle intentionally has no child-process implementation.
        if (childProcess == null || jsTypeOf(childProcess.spawnSync) != "function")
            return ProcessResult(-1, byteArrayOf(), "exec not available in browser".encodeToByteArray())
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
