package borg.trikeshed.platform

/** Process argv captured by native `main` entry points (lost when the orphaned Trikeshed/ tree was removed). */
object NativeMainArguments {
    var args: List<String> = emptyList()
}
