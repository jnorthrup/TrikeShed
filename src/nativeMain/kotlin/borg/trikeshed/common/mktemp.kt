package borg.trikeshed.common

import borg.trikeshed.lib.fromOctal
import kotlinx.cinterop.cstr
import kotlinx.cinterop.toKStringFromUtf8
import platform.posix.mkdtemp
import platform.posix.unlink

/** emulates shell command*/
actual fun mktemp(): String {
    //cinterop
    val template = "/tmp/tmpXXXXXX".cstr
    val res = mkdtemp(template)
    return res?.toKStringFromUtf8() ?: throw IllegalStateException("mkdtemp failed")

}

actual fun rm(path: String): Boolean {
    //cinterop
    return unlink(path) == 0
}

actual fun  mkdir(path: String): Boolean {
    //kotlin native posix make directory hierarchy
    val res = platform.posix.mkdir(path, 777.fromOctal().toUShort())
    return res == 0
}

