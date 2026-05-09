package borg.trikeshed.htx.client

data class Aria2Switches(
    val continueDownload: Boolean = false,
    val saveNotFound: Boolean = false,
    val split: Int = 1,
    val maxConcurrent: Int = 5,
    val dir: String = ".",
) {
    companion object {
        fun parse(args: List<String>): Aria2Switches {
            var continueDownload = false
            var saveNotFound = false
            var split = 1
            var maxConcurrent = 5
            var dir = "."
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
                    arg == "-d" || arg == "--dir" -> { i++; dir = args.getOrNull(i) ?: dir }
                    arg.startsWith("--dir=") -> dir = arg.substringAfter("=")
                }
                i++
            }
            return Aria2Switches(continueDownload, saveNotFound, split, maxConcurrent, dir)
        }
    }
}
