@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.channels.spi

import kotlinx.cinterop.*
import platform.posix.*

class PosixProcessOperations : ProcessOperations {

    override suspend fun exec(
        command: CharSequence,
        args: List<CharSequence>,
        stdin: ByteArray?,
        env: Map<CharSequence, CharSequence>,
    ): ProcessResult {
        val cmd = buildString { append(command); for (a in args) { append(" "); append(a) } }
        val fp = popen("$cmd 2>&1", "r") ?: return ProcessResult(-1, byteArrayOf(), "popen() failed".encodeToByteArray())
        val stdout = buildString {
            memScoped {
                val buf = allocArray<ByteVar>(4096)
                while (true) { val r = fgets(buf, 4096, fp) ?: break; append(r.toKString().trimEnd('\n')).append('\n') }
            }
        }
        val raw = pclose(fp)
        return ProcessResult(raw, stdout.trimEnd('\n').encodeToByteArray(), byteArrayOf())
    }
}
