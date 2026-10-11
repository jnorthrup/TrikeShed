package borg.trikeshed.userspace.nio.file.spi

import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import java.lang.foreign.*

class JvmSystemOperations : SystemOperations {

    override fun getenv(name: String, defaultVal: String?): String? =
        java.lang.System.getenv(name) ?: defaultVal

    override fun getProperty(name: String, defaultVal: String?): String? =
        java.lang.System.getProperty(name) ?: defaultVal

    override val homedir: String
        get() = java.lang.System.getProperty("user.home") ?: "/"

    /** libc getenv's bytes: the JDK's copy of the environment is decoded, invalid UTF-8 replaced. */
    override fun var_os(name: String): ByteArray? = Arena.ofConfined().use { arena ->
        val value = (Getenv.call.invokeExact(arena.allocateFrom(name)) as MemorySegment).reinterpret(Long.MAX_VALUE)
        if (value.address() == 0L) return null
        var length = 0L
        while (value.get(ValueLayout.JAVA_BYTE, length) != 0.toByte()) length++
        value.asSlice(0, length).toArray(ValueLayout.JAVA_BYTE)
    }

    object Getenv {
        val call = Linker.nativeLinker().let {
            it.downcallHandle(it.defaultLookup().find("getenv").orElseThrow(), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS))
        }
    }
}
