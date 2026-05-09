package borg.trikeshed.userspace.nio.channels.spi

class WasmProcessOperations : ProcessOperations {
    override suspend fun exec(
        command: String,
        args: List<String>,
        stdin: ByteArray?,
        env: Map<String, String>,
    ): ProcessResult = ProcessResult(-1, byteArrayOf(), "exec not available on WASM".encodeToByteArray())
}
