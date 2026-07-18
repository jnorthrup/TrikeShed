import platform.posix.*
import kotlinx.cinterop.*

@OptIn(ExperimentalForeignApi::class)
fun main() {
    val a = WIFEXITED(0)
    val b = WEXITSTATUS(0)
}
