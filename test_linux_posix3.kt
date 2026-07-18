import platform.posix.*
import kotlinx.cinterop.*

@OptIn(ExperimentalForeignApi::class)
fun main() {
    fork()
    execvpe("env", null, null)
    dup2(1, 2)
    pipe(null)
}
