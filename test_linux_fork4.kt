import platform.posix.*
import kotlinx.cinterop.*

@OptIn(ExperimentalForeignApi::class)
fun main() {
    val fd = open("test", O_RDONLY)
    dup2(fd, STDIN_FILENO)
}
