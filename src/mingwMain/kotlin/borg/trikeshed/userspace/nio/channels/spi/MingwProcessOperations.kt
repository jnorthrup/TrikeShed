package borg.trikeshed.userspace.nio.channels.spi

/** exec on Windows needs CreateProcess, which is not bound yet: exit -1 with the reason on stderr, as on Wasm. */
class MingwProcessOperations : ProcessOperations {
    override suspend fun exec(
        command: String,
        args: List<String>,
        stdin: ByteArray?,
        env: Map<String, String>,
    ): ProcessResult = ProcessResult(-1, byteArrayOf(), "exec not bound on Windows yet".encodeToByteArray())
}
