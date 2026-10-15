package borg.trikeshed.graal.subvm

import borg.trikeshed.btrfs.BtrfsWorldStore
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.vm.Teleported
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class PythonSqliteGuestTest {
    @Test
    fun pythonCallbacksAndCommittedRowsSurviveAnIsolateReopen() {
        val world = BtrfsWorldStore.ofMemory()
        val sqlite = "sqlite3/__init__.py"
        val source = Files.readString(Path.of("graalpy-sleeve/python", sqlite))
        repeat(2) {
            GraalBtrfsSupervisor("python-sqlite", VmFacet.GRAAL_PYTHON,
                budget = Budget(wallMillis = 30_000), world = world).use { guest ->
                PythonHostServices(guest, world).use { services ->
                    services.install()
                    guest.put("/workspace/$sqlite", source.encodeToByteArray())
                    PythonModules.install(guest, mapOf("sqlite3" to "/workspace/$sqlite"))
                    assertEquals(Teleported.Bool(true), guest.eval("""
                    import sys
                    sys.path.insert(0, '/workspace')
                    import sqlite3
                    db = sqlite3.connect('/profile/state.db', isolation_level=None)
                    db.row_factory = sqlite3.Row
                    assert db.execute('PRAGMA journal_mode').fetchone()[0] == 'memory'
                    db.create_function('reverse_text', 1, lambda value: value[::-1], deterministic=True)
                    db.execute('CREATE TABLE IF NOT EXISTS messages(id INTEGER PRIMARY KEY, body TEXT UNIQUE, data BLOB)')
                    if not db.execute('SELECT count(*) FROM messages').fetchone()[0]:
                        db.execute('INSERT INTO messages VALUES(?, ?, ?)', (9223372036854775807, 'Python', b'\x00\xff'))
                    db.execute('BEGIN IMMEDIATE')
                    db.execute("INSERT INTO messages(body) VALUES('discarded')")
                    db.rollback()
                    row = db.execute('SELECT id, reverse_text(body) AS text, data FROM messages').fetchone()
                    assert row['id'] == 9223372036854775807
                    assert row['TEXT'] == 'nohtyP' and row[2] == b'\x00\xff'
                    assert db.execute('SELECT count(*) FROM messages').fetchone()[0] == 1
                    db.close()
                    True
                    """.trimIndent()))
                }
            }
        }
    }
}
