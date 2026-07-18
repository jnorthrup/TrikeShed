import platform.posix.*
import kotlinx.cinterop.*
import platform.linux.*

@OptIn(ExperimentalForeignApi::class)
fun main() {
    val a = waitpid(-1, null, 0)
}
