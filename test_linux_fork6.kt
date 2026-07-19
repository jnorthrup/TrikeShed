import platform.posix.*
import kotlinx.cinterop.*
import platform.linux.*

@OptIn(ExperimentalForeignApi::class)
fun main() {
    val a = S_IRUSR
    val b = S_IWUSR
    val c = S_IRGRP
    val d = S_IROTH
}
