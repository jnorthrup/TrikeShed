# PRELOAD.md — libs/ebpf

Intent: An idempotent, syscall-grounded eBPF userspace JIT engine for the TrikeShed monorepo.
Provides: (1) a commonMain eBPF algebra, (2) verification, (3) userspace x86_64 + ARM64 JIT,
(4) streaming JIT builder pattern, (5) linuxMain io_uring BPF integration.

Design: pure algebra → verifier → JIT → execute. No platform-specific code in commonMain except
the `expect fun runNative`. All platform behavior is in `linuxMain` (mmap+PROT_EXEC + bpf syscall)
and `jvmMain` (stub).

API contracts:
- `EbpfInstruction` sealed hierarchy — every instruction is immutable.
- `verifyProgram(program)` → Success/Failure with per-instruction traces.
- `X86_64Jit.compile(program)` → `JitCode` (ByteArray) — raw machine code.
- `Arm64Jit.compile(program)` → `JitCode` — same ABI, different target.
- `EbpfJitEngine` — builder DSL: emit → verify → compile → execute.
- `UringEbpfEngine(uringFd, ebpfFd)` — linuxMain: attach eBPF to io_uring SQE filtering.

Streaming intent: programs are incrementally constructed via `EbpfBuilder` before verification.
Verification is a barrier — once verified, the program's instructions are immutable for JIT.
Verification produces traces (register states per instruction) for debugging and replay.

Kernel vs userspace:
- linuxMain: `UringEbpfEngine` → bpf() syscall → io_uring registration.
  The eBPF fd is obtained via BPF_PROG_LOAD, then attached via IORING_REGISTER_BPF_PROG.
- userspace commonMain: `X86_64Jit` / `Arm64Jit` → mmap+exec in linuxMain, interpret in JVM.

Benchmarking intent: the same eBPF program should produce identical results whether executed
via kernel io_uring (with actual BPF_PROG_LOAD) or userspace JIT. This enables cross-platform
benchmarking without requiring Linux kernel support on macOS/Windows.

Idempotent syscall grounding: all wire-format bytecode is encoded/decoded symmetrically.
The decoder (`decodeProgram`) and encoder (`EbpfEncoder.encode`) are inverse operations.

Precedence: this module's algebraic definitions are the canonical source for eBPF types
in TrikeShed. No duplicate `EbpfInstruction` or `Reg` types elsewhere.
