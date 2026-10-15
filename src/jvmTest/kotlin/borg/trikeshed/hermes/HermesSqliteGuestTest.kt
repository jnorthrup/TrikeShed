package borg.trikeshed.hermes

import borg.trikeshed.btrfs.BtrfsWorldStore
import borg.trikeshed.vm.Teleported
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class HermesSqliteGuestTest {
    @Test
    fun pythonCallbacksAndCommittedRowsSurviveAnIsolateReopen() {
        val world = BtrfsWorldStore.ofMemory()
        val sqlite = "sqlite3/__init__.py"
        val source = Files.readString(Path.of("graalpy-sleeve/hermes", sqlite))
        repeat(2) {
            HermesPythonPort(world = world).use { port ->
                val inventory = port.inventorySources(mapOf("entry" to ("entry.py" to """
                    import sqlite3
                    db = sqlite3.connect('/profile/state.db', isolation_level=None)
                    db.row_factory = sqlite3.Row
                    assert db.execute('PRAGMA journal_mode').fetchone()[0] == 'memory'
                    db.create_function('reverse_text', 1, lambda value: value[::-1], deterministic=True)
                    db.execute('CREATE TABLE IF NOT EXISTS messages(id INTEGER PRIMARY KEY, body TEXT UNIQUE, data BLOB)')
                    if not db.execute('SELECT count(*) FROM messages').fetchone()[0]:
                        db.execute('INSERT INTO messages VALUES(?, ?, ?)', (9223372036854775807, 'Hermes', b'\x00\xff'))
                    db.execute('BEGIN IMMEDIATE')
                    db.execute("INSERT INTO messages(body) VALUES('discarded')")
                    db.rollback()
                    row = db.execute('SELECT id, reverse_text(body) AS text, data FROM messages').fetchone()
                    assert row['id'] == 9223372036854775807
                    assert row['TEXT'] == 'semreH' and row[2] == b'\x00\xff'
                    assert db.execute('SELECT count(*) FROM messages').fetchone()[0] == 1
                    db.close()
                """.trimIndent())), sleeveSources = mapOf("sqlite3" to (sqlite to source)))
                assertEquals(Teleported.Bool(true), port.importInVm(inventory, "entry"))
            }
        }
    }
}
