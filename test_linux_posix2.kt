import platform.posix.*
import kotlinx.cinterop.*

@OptIn(ExperimentalForeignApi::class)
fun main() {
    memScoped {
        val actions = alloc<posix_spawn_file_actions_t>()
    }
}
