import platform.posix.*
import kotlinx.cinterop.*
import platform.linux.*

@OptIn(ExperimentalForeignApi::class)
fun main() {
    memScoped {
        val actions = alloc<posix_spawn_file_actions_t>()
    }
}
