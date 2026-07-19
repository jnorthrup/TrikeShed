import platform.posix.*
import kotlinx.cinterop.*
import platform.linux.*

@OptIn(ExperimentalForeignApi::class)
fun main() {
    memScoped {
        val pid = alloc<pid_tVar>()
        val actions = alloc<posix_spawn_file_actions_t>()
        posix_spawn_file_actions_init(actions.ptr)
        posix_spawnp(pid.ptr, "ls", actions.ptr, null, null, null)
    }
}
