package borg.trikeshed.common

import borg.trikeshed.lib.mktemp as libMktemp
import borg.trikeshed.lib.rm as libRm
import borg.trikeshed.lib.mkdir as libMkdir
import borg.trikeshed.lib.readLinesSeq as libReadLinesSeq
import borg.trikeshed.lib.readLines as libReadLines

actual val homedirGet: String
    get() = "/"

actual fun mktemp(): String = libMktemp()

actual fun rm(path: String): Boolean = libRm(path)

actual fun mkdir(path: String): Boolean = libMkdir(path)

actual fun readLinesSeq(path: String): Sequence<String> = libReadLinesSeq(path)

actual fun readLines(path: String): List<String> = libReadLines(path)
