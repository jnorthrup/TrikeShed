package borg.trikeshed.common

import borg.trikeshed.lib.jsHomeDir
import borg.trikeshed.lib.jsMktemp
import borg.trikeshed.lib.jsRm
import borg.trikeshed.lib.jsMkdir
import borg.trikeshed.lib.Files as LibFiles

actual val homedirGet: String
    get() = jsHomeDir()

actual fun mktemp(): String = jsMktemp()

actual fun rm(path: String): Boolean = jsRm(path)

actual fun mkdir(path: String): Boolean = jsMkdir(path)

actual fun readLinesSeq(path: String): Sequence<String> =
    LibFiles.readAllLines(path).asSequence()

actual fun readLines(path: String): List<String> =
    LibFiles.readAllLines(path)
