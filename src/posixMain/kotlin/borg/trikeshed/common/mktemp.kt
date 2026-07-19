@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.common

import borg.trikeshed.lib.fromOctal
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKStringFromUtf8
import kotlinx.cinterop.convert
import platform.posix.mkdtemp
import platform.posix.unlink

/** emulates shell command*/
actual fun mktemp(): String {
    return createTempDirectory("tmp")
}

internal fun createTempDirectory(prefix: String): String = memScoped {
    val template = "/tmp/$prefix-XXXXXX".encodeToByteArray()
    val buffer = allocArray<ByteVar>(template.size + 1)
    template.forEachIndexed { index, byte -> buffer[index] = byte }
    buffer[template.size] = 0
    mkdtemp(buffer)?.toKStringFromUtf8() ?: throw IllegalStateException("mkdtemp failed")
}

actual fun rm(path: String): Boolean {
    //cinterop
    return unlink(path) == 0
}

actual fun  mkdir(path: String): Boolean {
    //kotlin native posix make directory hierarchy
    val res = platform.posix.mkdir(path, 777.fromOctal().convert())
    return res == 0
}

