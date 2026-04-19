@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.common

import borg.trikeshed.lib.fromOctal
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.random.Random
import platform.posix.mkdir as posixMkdir
import platform.posix.remove
import platform.posix.rmdir

/** emulates shell command*/
actual fun mktemp(): String {
    return "/tmp/trikeshed-${Random.nextLong().toString(16)}.tmp"
}

actual fun rm(path: String): Boolean {
    return remove(path) == 0 || rmdir(path) == 0
}

actual fun  mkdir(path: String): Boolean {
    val normalized = path.trimEnd('/')
    if (normalized.isEmpty()) return true
    if (normalized == "/") return true

    val parent = normalized.substringBeforeLast('/', "")
    if (parent.isNotEmpty() && parent != normalized) {
        mkdir(parent)
    }

    return Files.exists(normalized) || posixMkdir(normalized, 777.fromOctal().toUShort()) == 0
}
