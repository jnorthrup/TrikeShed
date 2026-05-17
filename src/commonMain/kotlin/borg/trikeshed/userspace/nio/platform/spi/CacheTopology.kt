package borg.trikeshed.userspace.nio.platform.spi

class CacheTopology(
    val l1DataBytes: Long?,
    val l1InstructionBytes: Long? = null,
    val l2Bytes: Long? = null,
    val l3Bytes: Long? = null,
    val cacheLineBytes: Int? = null,
    val coreCount: Int? = null,
) { companion object { val UNKNOWN = CacheTopology(null, null, null, null, null, null) } }
