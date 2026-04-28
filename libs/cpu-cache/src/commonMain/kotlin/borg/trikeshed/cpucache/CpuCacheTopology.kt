package borg.trikeshed.cpucache

/**
 * Full cache topology for a single CPU core.
 *
 * All sizes in bytes. Null means unavailable/unknown on this platform.
 */
data class CpuCacheTopology(
    val l1DataBytes: Long?,
    val l1InstructionBytes: Long?,
    val l2Bytes: Long?,
    val l3Bytes: Long?,
    val cacheLineBytes: Int?,
    val coreCount: Int?,
)

/**
 * Platform-specific cache interrogation.
 *
 * Each platform source set provides an [actual] implementation.
 */
expect fun interrogateCpuCache(): CpuCacheTopology

/**
 * Format a [CpuCacheTopology] as Confix JSON —
 * a parseable medium consumable by TrikeShed's Confix parser.
 */
fun CpuCacheTopology.toConfix(): String = buildString {
    appendLine("{")
    appendLine("  \"l1DataBytes\": ${l1DataBytes ?: "null"},")
    appendLine("  \"l1InstructionBytes\": ${l1InstructionBytes ?: "null"},")
    appendLine("  \"l2Bytes\": ${l2Bytes ?: "null"},")
    appendLine("  \"l3Bytes\": ${l3Bytes ?: "null"},")
    appendLine("  \"cacheLineBytes\": ${cacheLineBytes ?: "null"},")
    appendLine("  \"coreCount\": ${coreCount ?: "null"}")
    append("}")
}
