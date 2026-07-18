import platform.posix.*
import kotlinx.cinterop.*
import platform.linux.*

@OptIn(ExperimentalForeignApi::class)
fun main() {
    val a = posix_spawnp(null, null, null, null, null, null)
}
