package borg.trikeshed.util.oroboros

/** Experiment event dumps are retained files, outside the document knowledge plane. */
object HermesCorpus {
    const val PREFIX = "homes/hermes/"

    fun isTrace(path: String): Boolean {
        val start = if (path.startsWith(PREFIX)) PREFIX.length else 0
        val hill = path.indexOf("hillclimb/", start)
        if (hill < 0 || hill != start && path[hill - 1] != '/') return false
        val lane = hill + "hillclimb/".length
        val trial = when {
            path.startsWith("asks/", lane) -> lane + "asks/".length
            path.startsWith("curation/", lane) -> lane + "curation/".length
            else -> return false
        }
        val end = path.indexOf('/', trial)
        if (end <= trial) return false
        return path.indexOf("/traces/", end) >= 0 || path.endsWith("/traces")
    }

    fun admits(path: String): Boolean = !path.startsWith(PREFIX) || !isTrace(path)
}
