package borg.trikeshed.userspace.nio.process

data class ProcessResult(
    val exitCode: Int,
    val stdout: ByteArray,
    val stderr: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProcessResult) return false
        return exitCode == other.exitCode && stdout.contentEquals(other.stdout) && stderr.contentEquals(other.stderr)
    }
    override fun hashCode(): Int = exitCode * 31 + stdout.contentHashCode() + stderr.contentHashCode()
}
