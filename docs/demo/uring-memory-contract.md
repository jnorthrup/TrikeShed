# Userspace memory and io_uring

`commonMain` owns memory lifetime, submission shape, registration, fallback,
bounded admission, completion delivery, and drain. All public `LiburingImpl`
actuals delegate the common `LiburingSession` and `EmulatedRing`. Platform
backends perform effects and report capabilities; callers use the same contract.

## Mapping, registration, submission

`mapMemory(address, length, protection, flags, fd, offset)` creates an independent
`MemoryMapping`. Arguments use Linux mmap values. Mappings can outlive their
file descriptors, and multiple mappings of one file are independent owners.
`sync` performs msync; `close` unmaps. Neither action is an SQE. Shared and private
mappings preserve their different writeback semantics.

`registerBuffers(Series<MemoryMapping>)` is a control call. The common ring retains
every owner until successful unregistration or backend teardown. Registration
cannot replace a live set, and staged fixed transfers prevent unregistration.
Closing retained memory fails before unmapping. The native implementation calls
`io_uring_register_buffers`; `READ_FIXED` and `WRITE_FIXED` carry the registered
index and its actual address. A backend without registration uses the common
buffered transfer fallback. Legacy `List<ByteBuffer>` registration is heap
emulation and does not claim kernel pinning.

`MADVISE` remains opcode 25: fd -1, full virtual address, byte length, offset 0.
Advice lives in `operationFlags`; `flags` is reserved for IOSQE flags. FADVISE is
opcode 24. For coroutine submission with automatic lifetime retention, use:

```kotlin
val memory = mapMemory(0, 65536, 3, 0x22, -1, 0) // anonymous, private, read/write
try {
    val request = UringOp.Companion.Submissions.madvise(
        memory, len = 65536, advice = 3, userData = 1,
    )
    channel.batchEnqueue(1 j { request })
} finally {
    memory.close()
}
```

The scoped common consumer releases its mapping borrow before publishing the
completion. Drain joins admitted work and does not depend on a caller resuming
to release memory. Internal retention uses common atomics independently of byte
access. The raw-address `prep*` signatures retain liburing's caller-owned lifetime
obligation: the caller must keep those addresses valid until completion.

`MADV_WILLNEED` requests readahead; it does not guarantee future fault-free access
or permanently resident pages. Mapping ownership and kernel buffer registration
are separate from this hint.

## Version and platform boundaries

The JNI build uses checksum-pinned **liburing 2.15**, with JNI ABI 3. This adds descriptor STATX with the userspace 24-byte metadata result; older JNI modules are rejected before selection. A supplied
`LIBURING_PREFIX` must provide 2.15 or later. `bin/build-jvm-uring.sh` builds on
Linux and fetches the pinned dependency when no prefix is supplied.

The common `UringOp` vocabulary is a supported subset: 58 entries with unique
codes and capability bits. The complete 2.15 header has 65 real opcodes. The
single-Long mask describes this subset; it is not a copy of the complete UAPI.
Native encoders use `code`, never enum ordinal. No MAP, MUNMAP or MSYNC opcode
exists in the common vocabulary.

Linux mapping effects use libc mmap/munmap/msync/madvise directly. JVM mappings
of existing Java FileChannels use JDK 25 arena mappings: this adapter explicitly
rejects ranges beyond EOF instead of silently growing the file, and PRIVATE maps
retain the JDK's narrower descriptor permissions. Darwin translates supported
Linux flag values and rejects unsupported advice. JS/Wasm report unsupported
memory mapping. Unsupported setup/SQE flags are rejected explicitly. This is not
a claim that every liburing 2.15 operation or every Linux VMA facility is exposed.

## Verification

Observed on 2026-09-09:

- `./gradlew jvmMainClasses compileKotlinMacos --console=plain` passed.
- The current-source JVM conformance suite passed 61 tests; its source hashes
  still matched after execution.
- The common coordinator passed native acceptance on Linux 6.8 arm64, JDK 25,
  liburing 2.15 and JNI ABI 2. Fixed and raw-address byte transfers, valid and
  invalid-address advice CQEs, registration lifetime, shared mapping after fd
  close, and synchronous writeback were exercised. Receipt, log and a repeatable
  runner are retained in `build/uring-jni-validation/`.
- Linux runtime evidence is for JVM/JNI. It does not claim execution of a
  Kotlin/Native Linux binary.

`scripts/verify-uring-conformance.sh` compiles current common/JVM IO sources ahead
of a recorded support JAR and runs the focused tests. It includes mapping,
registered-buffer fallback, ownership contention, cancellation and drain checks.
The support JAR supplies unchanged dependencies, not replacements for these IO
sources.

`UringNativeMemorySmoke` is an explicit Linux executable and fails if the required
native backend is unavailable. It exercises the same common coordinator with
native fixed and raw-address transfers, advice CQEs, anonymous registration,
mapping survival after fd close, msync persistence, and unregister before unmap.

Sources: [liburing 2.15 release](https://github.com/axboe/liburing/releases/tag/liburing-2.15),
[versioned helpers](https://github.com/axboe/liburing/blob/liburing-2.15/src/include/liburing.h),
[Linux mmap](https://man7.org/linux/man-pages/man2/mmap.2.html),
[Linux madvise](https://man7.org/linux/man-pages/man2/madvise.2.html).
