@file:OptIn(ExperimentalForeignApi::class)

package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import borg.trikeshed.userspace.nio.posix.trikeshed_args
import kotlinx.cinterop.*
import platform.posix.getenv as posix_getenv
import platform.posix.strlen

class PosixSystemOperations : SystemOperations {

    override fun getenv(name: String, defaultVal: String?): String? {
        val ptr = posix_getenv(name); return ptr?.toKString() ?: defaultVal
    }

    override fun getProperty(name: String, defaultVal: String?): String? = defaultVal

    override val homedir: String
        get() { val ptr = posix_getenv("HOME"); return ptr?.toKString() ?: "/tmp" }

    override fun var_os(name: String): ByteArray? = posix_getenv(name)?.let { it.readBytes(strlen(it).toInt()) }

    override fun args_os(): List<ByteArray> = memScoped {
        val argc = alloc<IntVar>()
        val argv = trikeshed_args(argc.ptr)!!
        List(argc.value) { argv[it]!!.let { arg -> arg.readBytes(strlen(arg).toInt()) } }
    }
}
