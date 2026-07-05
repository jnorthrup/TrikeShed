package borg.trikeshed.userspace.database

data class LsmrConfig(
    val path: String = "",
    val memtableThreshold: Int = 1024,
    val maxSegments: Int = 4,
)

class LsmrDatabase(val config: LsmrConfig = LsmrConfig()) {
    private val kv = linkedMapOf<String, ByteArray>()

    fun get(key: String): ByteArray? = kv[key]

    fun put(key: String, value: ByteArray) {
        kv[key] = value
    }

    fun remove(key: String): Boolean = kv.remove(key) != null

    fun keys(prefix: String = ""): List<String> = kv.keys.filter { it.startsWith(prefix) }
}
