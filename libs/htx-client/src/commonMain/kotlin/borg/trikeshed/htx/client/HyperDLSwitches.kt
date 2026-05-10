package borg.trikeshed.htx.client

data class HyperDLSwitches(
    val continueDownload: Boolean = false,
    val saveNotFound: Boolean = false,
    val split: Int = 5,                              // -s: connections per download
    val maxConcurrent: Int = 5,                      // -j: max parallel downloads
    val maxConnectionPerServer: Int = 1,             // -x: max conns to same host (default 1 since 1.10)
    val minSplitSize: Long = 20L * 1024 * 1024,     // -k: 20 MiB default, 2× threshold
    val pieceLength: Long = 1024 * 1024,             // internal, aria2c defaults to 1 MiB
    val dir: String = ".",
    val out: String? = null,                         // -o: output filename
    val forceSequential: Boolean = false,            // -Z: sequential mode
    val fileAllocation: String = "prealloc",         // --file-allocation: none|prealloc|trunc|falloc
) {
    companion object {
        fun parse(args: List<String>): HyperDLSwitches {
            var continueDownload = false
            var saveNotFound = false
            var split = 5
            var maxConcurrent = 5
            var maxConnectionPerServer = 1
            var minSplitSize = 20L * 1024 * 1024
            var pieceLength = 1024L * 1024
            var dir = "."
            var out: String? = null
            var forceSequential = false
            var fileAllocation = "prealloc"
            var i = 0
            while (i < args.size) {
                val arg = args[i]
                when {
                    arg == "-c" -> continueDownload = true
                    arg == "--save-not-found" -> {
                        val next = args.getOrNull(i + 1)
                        if (next != null && !next.startsWith("-")) { i++; saveNotFound = next.toBooleanStrictOrNull() ?: false }
                        else saveNotFound = true
                    }
                    arg.startsWith("--save-not-found=") -> saveNotFound = arg.substringAfter("=").toBooleanStrictOrNull() ?: true
                    arg == "-s" || arg == "--split" -> { i++; split = args.getOrNull(i)?.toIntOrNull() ?: split }
                    arg.startsWith("--split=") -> split = arg.substringAfter("=").toIntOrNull() ?: split
                    arg == "-j" || arg == "--max-concurrent-downloads" -> { i++; maxConcurrent = args.getOrNull(i)?.toIntOrNull() ?: maxConcurrent }
                    arg.startsWith("--max-concurrent-downloads=") -> maxConcurrent = arg.substringAfter("=").toIntOrNull() ?: maxConcurrent
                    arg == "-x" || arg == "--max-connection-per-server" -> { i++; maxConnectionPerServer = args.getOrNull(i)?.toIntOrNull() ?: maxConnectionPerServer }
                    arg.startsWith("--max-connection-per-server=") -> maxConnectionPerServer = arg.substringAfter("=").toIntOrNull() ?: maxConnectionPerServer
                    arg == "-k" || arg == "--min-split-size" -> { i++; minSplitSize = parseSize(args.getOrNull(i)) ?: minSplitSize }
                    arg.startsWith("--min-split-size=") -> minSplitSize = parseSize(arg.substringAfter("=")) ?: minSplitSize
                    arg == "--piece-length" -> { i++; pieceLength = parseSize(args.getOrNull(i)) ?: pieceLength }
                    arg.startsWith("--piece-length=") -> pieceLength = parseSize(arg.substringAfter("=")) ?: pieceLength
                    arg == "-o" || arg == "--out" -> { i++; out = args.getOrNull(i) }
                    arg.startsWith("--out=") -> out = arg.substringAfter("=")
                    arg == "-d" || arg == "--dir" -> { i++; dir = args.getOrNull(i) ?: dir }
                    arg.startsWith("--dir=") -> dir = arg.substringAfter("=")
                    arg == "-Z" || arg == "--force-sequential" -> forceSequential = true
                    arg == "--file-allocation" -> { i++; fileAllocation = args.getOrNull(i) ?: fileAllocation }
                    arg.startsWith("--file-allocation=") -> fileAllocation = arg.substringAfter("=")
                }
                i++
            }
            return HyperDLSwitches(continueDownload, saveNotFound, split, maxConcurrent, maxConnectionPerServer,
                minSplitSize, pieceLength, dir, out, forceSequential, fileAllocation)
        }
    }
}

private fun parseSize(s: String?): Long? {
    if (s == null) return null
    val v = s.trim()
    if (v.isEmpty()) return null
    val multiplier = when {
        v.endsWith("G", ignoreCase = true) -> 1024L * 1024 * 1024
        v.endsWith("M", ignoreCase = true) -> 1024L * 1024
        v.endsWith("K", ignoreCase = true) -> 1024L
        else -> 1L
    }
    val num = if (multiplier > 1) v.dropLast(1) else v
    return num.toLongOrNull()?.let { it * multiplier }
}
