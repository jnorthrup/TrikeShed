import platform.posix.*
import kotlinx.cinterop.*

@OptIn(ExperimentalForeignApi::class)
fun main() {
    memScoped {
        val pid = fork()
        if (pid == 0) {
            val argv = allocArray<CPointerVar<ByteVar>>(3)
            argv[0] = "env".cstr.ptr
            argv[1] = "A=B".cstr.ptr
            argv[2] = null

            execve("env", argv, null)
        }
    }
}
