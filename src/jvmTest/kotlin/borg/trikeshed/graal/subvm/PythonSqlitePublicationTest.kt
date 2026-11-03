package borg.trikeshed.graal.subvm

import borg.trikeshed.lib.*
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class PythonSqlitePublicationTest {
    @Test fun competingServicesCannotOverwriteOneAnotherOnTheSameMount() {
        val vfs = TrikeShedGraalVfs()
        val path = "/workspace/test.sqlite"
        val armed = AtomicBoolean(false)
        val boundary = CyclicBarrier(2)
        fun sqlite() = PythonSqlite(vfs::fetch, { name, expected, image ->
            if (armed.get()) boundary.await(20, TimeUnit.SECONDS)
            vfs.putIf(name, expected, image)
        }, { _, _ -> null })
        fun connect(sqlite: PythonSqlite): Int {
            val response = sqlite.call(_m["op" j "connect", "path" j path])
            assertNull(response["error"], response.toString())
            return ((response["value"] as Map<*, *>)["id"] as Number).toInt()
        }
        fun execute(sqlite: PythonSqlite, id: Int, sql: String): Map<String, Any?> =
            sqlite.call(_m["op" j "execute", "id" j id, "sql" j sql])
        val setup = sqlite()
        val setupId = connect(setup)
        val created = execute(setup, setupId, "CREATE TABLE rows (value INTEGER)")
        assertNull(created["error"])
        val lease = setup.call(_m["op" j "lease", "id" j setupId])
        assertEquals((lease["value"] as Map<*, *>)["generation"], (created["value"] as Map<*, *>)["generation"])
        setup.close()
        val first = sqlite()
        val second = sqlite()
        val firstId = connect(first)
        val secondId = connect(second)
        val a = AtomicReference<Map<String, Any?>>()
        val b = AtomicReference<Map<String, Any?>>()
        val firstWriter = Thread { a.set(execute(first, firstId, "INSERT INTO rows VALUES (1)")) }
        val secondWriter = Thread { b.set(execute(second, secondId, "INSERT INTO rows VALUES (2)")) }
        try {
            armed.set(true)
            firstWriter.start(); secondWriter.start()
            firstWriter.join(25_000); secondWriter.join(25_000)
            assertFalse(firstWriter.isAlive || secondWriter.isAlive, "writers did not finish")
            val responses = _l[assertNotNull(a.get()), assertNotNull(b.get())]
            assertEquals(1, responses.count { it["error"] == null }, responses.toString())
            assertEquals(1, responses.count { it["error"] != null }, responses.toString())
        } finally {
            armed.set(false)
            boundary.reset()
            firstWriter.join(25_000); secondWriter.join(25_000)
            first.close(); second.close()
        }
        val reader = sqlite()
        try {
            val id = connect(reader)
            val read = execute(reader, id, "SELECT value FROM rows")
            assertNull(read["error"], read.toString())
            val rows = (read["value"] as Map<*, *>)["rows"] as List<*>
            assertEquals(1, rows.size)
            val value = ((rows.single() as List<*>).single() as Map<*, *>)["v"]
            assertTrue(value == "1" || value == "2")
        } finally { reader.close() }
    }
}
