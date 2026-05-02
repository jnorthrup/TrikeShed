package borg.trikeshed.cpucache

/**
 * Entry point — interrogates CPU cache and prints topology.
 *
 * Platform-specific [interrogateCpuCache] is resolved at link time.
 * Output formats:
 *   - Default: Confix JSON (parseable by TrikeShed's Confix parser)
 *   - --mlir: MLIR assembly format (cpu_cache dialect)
 */
fun main(args: Array<String>) {
    val topology = interrogateCpuCache()
    
    val output = when {
        args.isNotEmpty() && args[0] == "--mlir" -> topology.asMlir
        else -> topology.toConfix()
    }
    
    println(output)
}
