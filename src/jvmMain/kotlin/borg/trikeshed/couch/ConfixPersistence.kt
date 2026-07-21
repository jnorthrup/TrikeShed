package borg.trikeshed.couch

import borg.trikeshed.parse.confix.Confix
import borg.trikeshed.parse.confix.ConfixArray
import borg.trikeshed.parse.confix.ConfixObject
import borg.trikeshed.parse.confix.ConfixPrimitive
import java.nio.file.Files
import java.nio.file.Path

class ConfixPersistence(private val directory: Path) {

    fun load(id: String): CouchDocument? {
        val path = directory.resolve("$id.json")
        if (!Files.exists(path)) return null
        val text = Files.readString(path)
        return Confix.decode(text)
    }

    fun save(doc: CouchDocument) {
        val path = directory.resolve("${doc.id}.json")
        Files.createDirectories(path.parent)
        Files.writeString(path, Confix.encode(doc))
    }
    
    fun delete(id: String) {
        val path = directory.resolve("$id.json")
        Files.deleteIfExists(path)
    }
}
