import borg.trikeshed.userspace.nio.platform.spi.SystemOperations

fun main() {
    println(SystemOperations.default.getenv("PATH"))
}
