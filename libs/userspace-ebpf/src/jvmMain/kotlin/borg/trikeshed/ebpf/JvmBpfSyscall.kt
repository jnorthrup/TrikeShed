package borg.trikeshed.ebpf

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Memory
import com.sun.jna.Platform

interface LinuxCLibrary : Library {
    // long syscall(long number, ...);
    fun syscall(number: Long, vararg args: Any): Long
}

class JvmBpfSyscall : BpfSyscall {

    private val SYS_bpf = 321L // Linux x86_64 syscall number for bpf()

    private val libc: LinuxCLibrary? = try {
        if (Platform.isLinux()) {
            Native.load("c", LinuxCLibrary::class.java)
        } else null
    } catch (e: Exception) {
        null
    }

    override fun bpf(cmd: Int, attr: ByteArray, size: Int): Int {
        if (libc == null) {
            // Not on Linux, or libc not available
            return -1
        }

        // The common code payload includes:
        // 48 bytes (attr) + N bytes (instructions) + M bytes (license string)
        // We need to parse it, set up native memory for pointers, and invoke syscall.

        val insnCnt = (attr[4].toInt() and 0xFF) or
                      ((attr[5].toInt() and 0xFF) shl 8) or
                      ((attr[6].toInt() and 0xFF) shl 16) or
                      ((attr[7].toInt() and 0xFF) shl 24)

        val insnSize = insnCnt * 8
        val baseOffset = 48

        // Allocate native memory for instructions
        val insnMem = Memory(insnSize.toLong())
        insnMem.write(0, attr, baseOffset, insnSize)

        // Read license string length
        var licenseLen = 0
        var i = baseOffset + insnSize
        while (i < attr.size && attr[i] != 0.toByte()) {
            licenseLen++
            i++
        }

        // Allocate native memory for license
        val licenseMem = Memory((licenseLen + 1).toLong())
        licenseMem.write(0, attr, baseOffset + insnSize, licenseLen + 1)

        // Allocate native memory for the main bpf_attr struct
        val attrMem = Memory(48)
        attrMem.write(0, attr, 0, 48)

        // Inject pointers (x86_64 uses 64-bit pointers)
        // offset 8: __aligned_u64 insns;
        attrMem.setPointer(8, insnMem)
        // offset 16: __aligned_u64 license;
        attrMem.setPointer(16, licenseMem)

        // Log buffer (optional for debug, we skip for now)
        attrMem.setLong(24, 0) // log_level = 0
        attrMem.setInt(28, 0)  // log_size = 0
        attrMem.setLong(32, 0) // log_buf = NULL

        // int bpf(int cmd, union bpf_attr *attr, unsigned int size);
        val result = libc.syscall(SYS_bpf, cmd.toLong(), attrMem, 48L)

        return result.toInt()
    }
}
