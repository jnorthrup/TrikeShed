import platform.posix.*
import kotlinx.cinterop.*
import platform.linux.*

@OptIn(ExperimentalForeignApi::class)
fun main() {
    val a = STDIN_FILENO
    val b = STDOUT_FILENO
    val c = STDERR_FILENO
}
