package borg.trikeshed.userspace.nio.channels.spi

class WasmProcessOperations : ProcessOperations {
    override suspend fun exec(
        command: CharSequence,
        args: List<CharSequence>,
        stdin: ByteArray?,
        env: Map<CharSequence, CharSequence>,
    ): ProcessResult = ProcessResult(-1, byteArrayOf(), "exec not available on WASM".encodeToByteArray())
}
