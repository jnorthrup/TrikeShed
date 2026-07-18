import platform.posix.*
import kotlinx.cinterop.*
import platform.linux.*

@OptIn(ExperimentalForeignApi::class)
fun main() {
    val exitCode = memScoped {
        val pid = fork()
        if (pid == -1) {
            return@memScoped -1
        } else if (pid == 0) {
            _exit(127)
            0 // unreachable, but makes it return int
        } else {
            0
        }
    }
}
