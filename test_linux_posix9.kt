import platform.posix.*
import kotlinx.cinterop.*
import platform.linux.*

@OptIn(ExperimentalForeignApi::class)
fun main() {
    execvp("a", null)
}
