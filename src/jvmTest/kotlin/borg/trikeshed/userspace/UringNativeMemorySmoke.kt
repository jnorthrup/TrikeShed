package borg.trikeshed.userspace

import borg.trikeshed.lib.j
import java.nio.file.Files

/** Explicit Linux acceptance executable: unavailable native execution is a failure, never a skip. */
object UringNativeMemorySmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        val discovery = discoverJvmUringBackend(8)
        println(discovery.report.description)
        val backend = checkNotNull(discovery.backend) { "Native JNI backend is required" }
        val required = UringOp.caps(UringOp.READ, UringOp.WRITE, UringOp.READ_FIXED, UringOp.WRITE_FIXED,
            UringOp.MADVISE, UringOp.FADVISE, UringOp.OPENAT, UringOp.CLOSE)
        check(backend.nativeCapabilities and required == required) {
            "Kernel probe lacks the native operations exercised by this acceptance check"
        }
        val ring = EmulatedRing(backend)
        ring.open(8, 0).getOrThrow()
        val page = JvmMemorySyscalls.pageSize.toInt()
        val file = Files.createTempFile("trikeshed-uring-memory-", ".bin")
        val memory = mapMemory(0, page.toLong(), 3, 0x22, -1, 0)
        var fileMapping: MemoryMapping? = null
        var fd = -1
        var token = 0L

        fun completed(expected: Int? = null): Int {
            check(ring.submit().getOrThrow() == 1)
            val completion = checkNotNull(ring.waitCqe().getOrThrow())
            check(completion.userData == token) { "CQE token differs from admitted request" }
            if (expected != null) check(completion.res == expected) {
                "CQE $token expected $expected, got ${completion.res}"
            }
            check(ring.waitCqe().getOrThrow() == null) { "CQE was delivered more than once" }
            return completion.res
        }

        try {
            ring.prepOpenat(-100, file.toString(), 2, 0, ++token).getOrThrow()
            fd = completed().also { check(it >= 0) }
            val expected = ByteArray(page) { (it * 31).toByte() }
            memory.write(0, expected)
            ring.registerBuffers(1 j { memory }).getOrThrow()
            check(ring.registerBuffers(1 j { memory }).isFailure) { "Registration silently replaced live buffers" }
            check(runCatching { memory.close() }.isFailure) { "Registered mapping was unmapped" }
            check(ring.prepReadFixed(fd, 1, page, 0, token + 1).isFailure) { "Unregistered buffer index was admitted" }

            ring.prepWriteFixed(fd, 0, page, 0, ++token).getOrThrow()
            check(ring.unregisterBuffers().isFailure) { "Registration released a staged fixed transfer" }
            completed(page)
            check(Files.readAllBytes(file).contentEquals(expected)) { "Fixed write bytes differ" }

            memory.write(0, ByteArray(page))
            ring.prepReadFixed(fd, 0, page, 0, ++token).getOrThrow()
            completed(page)
            val received = ByteArray(page)
            memory.read(0, received)
            check(received.contentEquals(expected)) { "Fixed read bytes differ" }

            memory.write(0, ByteArray(page))
            ring.prepRead(fd, memory.address, page, 0, ++token).getOrThrow()
            completed(page)
            memory.read(0, received)
            check(received.contentEquals(expected)) { "Raw-address read bytes differ" }
            ring.prepWrite(fd, memory.address, page, 0, ++token).getOrThrow()
            completed(page)

            ring.prepMadvise(memory.address, page, 3, ++token).getOrThrow()
            completed(0)
            ring.prepMadvise(memory.address + 1, page - 1, 3, ++token).getOrThrow()
            completed(-22)
            ring.prepFadvise(fd, 0, 0, 3, ++token).getOrThrow()
            completed(0)

            fileMapping = mapMemory(0, page.toLong(), 3, 1, fd, 0)
            ring.prepClose(fd, ++token).getOrThrow()
            completed(0)
            fd = -1
            check(fileMapping.isOpen && fileMapping[1] == expected[1]) { "Closing fd invalidated mapping" }
            fileMapping[0] = 0x5a.toByte()
            fileMapping.sync()
            check(Files.readAllBytes(file)[0] == 0x5a.toByte()) { "MAP_SHARED msync did not persist bytes" }
            ring.prepMadvise(fileMapping.address, page, 3, ++token).getOrThrow()
            completed(0)

            ring.unregisterBuffers().getOrThrow()
            check(ring.prepReadFixed(-1, 0, page, 0, token + 1).isFailure) { "Fixed transfer survived unregistration" }
            memory.close()
            check(!memory.isOpen)
            println("PASS native CQEs: READ_FIXED, WRITE_FIXED, raw-address READ/WRITE, MADVISE, FADVISE; anonymous registration retention; MAP_SHARED survives fd close; msync persistence; unregister then munmap")
        } finally {
            ring.close().getOrThrow()
            fileMapping?.close()
            memory.close()
            Files.deleteIfExists(file)
        }
    }
}
