@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.channels.spi

import kotlinx.cinterop.*
import platform.posix.*

class PosixProcessOperations : ProcessOperations {

    override fun exec(command: String, vararg args: String): ExecResult {
        val cmd = buildString { append(command); for (a in args) { append(" "); append(a) } }
        val fp = popen("$cmd 2>&1", "r") ?: return ExecResult(-1, "", "popen() failed")
        val stdout = buildString {
            memScoped {
                val buf = allocArray<ByteVar>(4096)
                while (true) { val r = fgets(buf, 4096, fp) ?: break; append(r.toKString().trimEnd('\n')).append('\n') }
            }
        }
        val raw = pclose(fp)
        return ExecResult(raw, stdout.trimEnd('\n'), "")
    }
}
