package borg.trikeshed.cpucache

/**
 * Entry point — interrogates CPU cache and prints topology.
 *
 * Platform-specific [interrogateCpuCache] is resolved at link time.
 * Output formats:
 *   - Default: Confix JSON (parseable by TrikeShed's Confix parser)
 *   - --mlir: MLIR assembly format (cpu_cache dialect)
 *   - --llvm: LLVM dialect MLIR module (sysconf calls)
 */
// RLM: library entrypoint commented out - fun main(args: Array<String>) {
// RLM: library entrypoint commented out -     val topology = interrogateCpuCache()

    // RLM: library entrypoint commented out - val topology = interrogateCpuCache()
// RLM: library entrypoint commented out -     val output = when {
// RLM: library entrypoint commented out -         args.isNotEmpty() && args[0] == "--mlir" -> topology.asMlir
// RLM: library entrypoint commented out -         args.isNotEmpty() && args[0] == "--llvm" -> CpuCacheMlir.toLlvmDialrectModule(topology)
// RLM: library entrypoint commented out -         else -> topology.toConfix()
// RLM: library entrypoint commented out -     }
// RLM: library entrypoint commented out -     println(output)
//}
