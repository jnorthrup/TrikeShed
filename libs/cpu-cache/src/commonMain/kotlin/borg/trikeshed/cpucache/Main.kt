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
fun main(args: Array<String>) {
    val topology = interrogateCpuCache()
    
    val output = when {
        args.isNotEmpty() && args[0] == "--mlir" -> topology.asMlir
        args.isNotEmpty() && args[0] == "--llvm" -> CpuCacheMlir.toLlvmDialrectModule(topology)
        else -> topology.toConfix()
    }
    
    println(output)
}
