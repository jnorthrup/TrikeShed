package borg.trikeshed.userspace.reactor

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

/**
 * Serialization container for CacheEntry list.
 * Must be top-level for kotlinx.serialization plugin to generate serializer.
 */
@Serializable
data class CacheSnapshot(val entries: List<CacheEntry>)

object CacheStoreJvm {

    fun loadEntries(path: Path): List<CacheEntry> {
        if (!Files.exists(path)) return emptyList()
        val text = Files.readString(path)
        return decodeEntries(text)
    }

    fun saveEntries(path: Path, entries: List<CacheEntry>) {
        val container = CacheSnapshot(entries)
        Files.createDirectories(path.parent ?: Path.of("."))
        Files.writeString(path, borg.trikeshed.parse.confix.Confix.encode(container))
    }

    fun decodeEntries(text: String): List<CacheEntry> {
        if (text.isBlank()) return emptyList()
        val container = borg.trikeshed.parse.confix.Confix.decode<CacheSnapshot>(text)
        return container.entries
    }
}
