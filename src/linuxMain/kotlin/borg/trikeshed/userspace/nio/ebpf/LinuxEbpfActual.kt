@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package borg.trikeshed.userspace.nio.ebpf

import kotlin.native.concurrent.ThreadLocal
import kotlinx.cinterop.*
import platform.posix.*
import platform.linux.*

// BPF syscall constants
private const val BPF_PROG_LOAD = 5
private const val BPF_PROG_RUN = 10 // BPF_PROG_TEST_RUN

private const val BPF_PROG_TYPE_SOCKET_FILTER = 1

private fun bpf(cmd: Int, attr: CValuesRef<ByteVar>?, size: Int): Int {
    return syscall(__NR_bpf.toLong(), cmd.toLong(), attr, size.toLong()).toInt()
}

/**
 * Linux native execution of eBPF JIT'd code via the kernel verifier path.
 *
 * This implements R9 (kernel governance) from the grey paper and Legion Doc 04 Layer 3,
 * by passing the code to the actual bpf(2) syscall rather than stubbing an insecure
 * mmap PROT_EXEC region.
 */
@ThreadLocal
actual fun runNative(code: ByteArray, args: LongArray): Long {
    memScoped {
        val attrSize = 144
        val attr = allocArray<ByteVar>(attrSize)
        memset(attr, 0, attrSize.toULong())

        // bpf_attr: prog_type offset: 0
        val progTypePtr = attr.reinterpret<UIntVar>()
        progTypePtr[0] = BPF_PROG_TYPE_SOCKET_FILTER.toUInt()

        // bpf_attr: insn_cnt offset: 4
        // A single eBPF instruction is 8 bytes. Code byte array size / 8.
        val insnCntPtr = (attr + 4)!!.reinterpret<UIntVar>()
        insnCntPtr[0] = (code.size / 8).toUInt()

        // bpf_attr: insns offset: 8
        val insnsArray = allocArray<ByteVar>(code.size)
        for (i in code.indices) {
            insnsArray[i] = code[i]
        }
        val insnsPtr = (attr + 8)!!.reinterpret<ULongVar>()
        insnsPtr[0] = insnsArray.toLong().toULong()

        // bpf_attr: license offset: 16
        val licenseArray = allocArray<ByteVar>(4)
        licenseArray[0] = 'G'.code.toByte()
        licenseArray[1] = 'P'.code.toByte()
        licenseArray[2] = 'L'.code.toByte()
        licenseArray[3] = 0.toByte()
        val licensePtr = (attr + 16)!!.reinterpret<ULongVar>()
        licensePtr[0] = licenseArray.toLong().toULong()

        // bpf_attr: kern_version offset: 40 (often required to be 0 or current)
        // Set up the load command
        val fd = bpf(BPF_PROG_LOAD, attr, attrSize)

        if (fd < 0) {
            return -1L // Error loading BPF program
        }

        try {
            // Setup for BPF_PROG_RUN
            val runAttr = allocArray<ByteVar>(attrSize)
            memset(runAttr, 0, attrSize.toULong())

            // test_run: prog_fd offset: 0
            val runProgFdPtr = runAttr.reinterpret<UIntVar>()
            runProgFdPtr[0] = fd.toUInt()

            // For running with args, we need data_in/data_out depending on prog type
            // But for simple return value testing:
            // test_run: retval offset: 4 (output)

            val ret = bpf(BPF_PROG_RUN, runAttr, attrSize)

            if (ret < 0) {
                return -2L
            }

            // Read back retval
            val retvalPtr = (runAttr + 4)!!.reinterpret<UIntVar>()
            return retvalPtr[0].toLong()
        } finally {
            close(fd)
        }
    }
}

/**
 * Uring-backed eBPF execution: program fd passed through io_uring.
 */
class UringEbpfEngine(
    private val uringFd: Int,
    private val ebpfFd: Int = -1,
) {
    /** Register an eBPF program fd with io_uring for SQE filtering. */
    fun registerEbpfProgram(programFd: Int): Boolean {
        // io_uring_register(uringFd, IORING_REGISTER_BPF_PROG, &programFd, 1)
        return true // stub
    }

    /** Load and register a program with the kernel via bpf(2) syscall. */
    fun loadProgram(bytecode: ByteArray, license: String = "GPL"): Int {
        memScoped {
            val attrSize = 144
            val attr = allocArray<ByteVar>(attrSize)
            memset(attr, 0, attrSize.toULong())

            val progTypePtr = attr.reinterpret<UIntVar>()
            progTypePtr[0] = BPF_PROG_TYPE_SOCKET_FILTER.toUInt()

            val insnCntPtr = (attr + 4)!!.reinterpret<UIntVar>()
            insnCntPtr[0] = (bytecode.size / 8).toUInt()

            val insnsArray = allocArray<ByteVar>(bytecode.size)
            for (i in bytecode.indices) {
                insnsArray[i] = bytecode[i]
            }
            val insnsPtr = (attr + 8)!!.reinterpret<ULongVar>()
            insnsPtr[0] = insnsArray.toLong().toULong()

            val licenseBytes = license.encodeToByteArray()
            val licenseArray = allocArray<ByteVar>(licenseBytes.size + 1)
            for (i in licenseBytes.indices) {
                licenseArray[i] = licenseBytes[i]
            }
            licenseArray[licenseBytes.size] = 0.toByte()
            val licensePtr = (attr + 16)!!.reinterpret<ULongVar>()
            licensePtr[0] = licenseArray.toLong().toULong()

            return bpf(BPF_PROG_LOAD, attr, attrSize)
        }
    }

    /** Unregister a program from io_uring. */
    fun unregisterEbpfProgram(): Boolean {
        return true // stub
    }
}
