package borg.trikeshed.graal.vitals

/** Copied coordinates only: never retain a JFR event, class, thread or classloader. */
data class AllocationFrame(
    val className: String,
    val method: String,
    val descriptor: String,
    val bci: Int,
    val line: Int,
    val execution: String,
    val loader: String,
    val loaderId: Long,
) {
    fun wire(): Map<String, Any?> = mapOf(
        "class" to className, "method" to method, "descriptor" to descriptor,
        "bci" to bci, "line" to line, "execution" to execution,
        "loader" to loader, "loaderId" to loaderId,
    )
}

/** Bounded rolling attribution. Missing stacks and capacity loss are reported, never hidden. */
class AllocationSites(private val capacity: Int = 512) {
    private data class SiteKey(val allocated: String, val frames: List<AllocationFrame>, val truncated: Boolean)
    private class Counts {
        val seconds = LongArray(31) { Long.MIN_VALUE }
        val bytes = LongArray(31)
        val samples = LongArray(31)
        fun add(second: Long, weight: Long) {
            val i = Math.floorMod(second, 31).toInt()
            if (seconds[i] != second) { seconds[i] = second; bytes[i] = 0; samples[i] = 0 }
            bytes[i] += weight
            samples[i]++
        }
        fun sum(now: Long): Pair<Long, Long> {
            var weight = 0L
            var count = 0L
            for (i in seconds.indices) if (seconds[i] > now - 30 && seconds[i] <= now) {
                weight += bytes[i]; count += samples[i]
            }
            return weight to count
        }
    }
    private class Site(val id: String, val key: SiteKey, var lastSecond: Long) {
        val counts = Counts()
    }
    private val sites = LinkedHashMap<SiteKey, Site>()
    private val omitted = Counts()
    private var nextId = 0L

    @Synchronized
    fun record(allocated: String, weight: Long, frames: List<AllocationFrame>, truncated: Boolean, atMs: Long) {
        if (weight <= 0) return
        val second = atMs / 1000
        val key = SiteKey(allocated, frames.take(MAX_FRAMES), truncated || frames.size > MAX_FRAMES)
        var site = sites[key]
        if (site == null) {
            if (sites.size >= capacity) sites.entries.removeIf { it.value.lastSecond <= second - 30 }
            if (sites.size >= capacity) { omitted.add(second, weight); return }
            site = Site((++nextId).toString(), key, second)
            sites[key] = site
        }
        site.lastSecond = maxOf(site.lastSecond, second)
        site.counts.add(second, weight)
    }

    @Synchronized
    fun frame(siteId: String, index: Int, nowMs: Long): AllocationFrame? =
        sites.values.firstOrNull { it.id == siteId && it.lastSecond > nowMs / 1000 - 30 }
            ?.key?.frames?.getOrNull(index)

    @Synchronized
    fun snapshot(allocated: String?, nowMs: Long): Map<String, Any?> {
        val second = nowMs / 1000
        sites.entries.removeIf { it.value.lastSecond <= second - 30 }
        val active = sites.values.map { it to it.counts.sum(second) }.filter { it.second.second > 0 }
        val classes = active.groupBy { it.first.key.allocated }.map { (name, group) ->
            mapOf("class" to name, "bytes" to group.sumOf { it.second.first }, "samples" to group.sumOf { it.second.second })
        }.sortedByDescending { it["bytes"] as Long }
        val selected = active.filter { allocated == null || it.first.key.allocated == allocated }
            .sortedByDescending { it.second.first }
        val rows = selected.take(64).map { (site, count) ->
            mapOf("id" to site.id, "class" to site.key.allocated, "bytes" to count.first,
                "samples" to count.second, "truncated" to site.key.truncated,
                "frames" to site.key.frames.map(AllocationFrame::wire))
        }
        val dropped = omitted.sum(second)
        return mapOf(
            "class" to allocated, "windowSeconds" to 30, "fromMs" to (second - 29) * 1000,
            "toMs" to nowMs, "bytes" to selected.sumOf { it.second.first },
            "samples" to selected.sumOf { it.second.second }, "classes" to classes, "sites" to rows,
            "omittedSiteRows" to maxOf(0, selected.size - rows.size),
            "capacity" to capacity, "omittedBytes" to dropped.first, "omittedSamples" to dropped.second,
            "measurement" to "JFR weighted allocation samples; not retained objects",
        )
    }

    companion object { const val MAX_FRAMES = 24 }
}
