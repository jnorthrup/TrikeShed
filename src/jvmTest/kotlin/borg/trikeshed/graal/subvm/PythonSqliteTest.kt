package borg.trikeshed.graal.subvm

import kotlin.test.*

class PythonSqliteTest {
    fun value(port: PythonSqlite, op: String, vararg fields: Pair<String, Any?>): Any? {
        val reply = port.call(mapOf("op" to op, *fields))
        assertFalse("error" in reply, reply.toString())
        return reply["value"]
    }

    @Test
    fun sqlTransactionsPersistOnlyCommittedImagesAndReopen() {
        val images = mutableMapOf<String, ByteArray>()
        PythonSqlite({ images[it]?.copyOf() }, { path, bytes -> images[path] = bytes.copyOf() }, { _, _ -> error("no callback") }).use { port ->
            val opened = value(port, "connect", "path" to "/profile/state.db") as Map<*, *>
            val id = opened["id"]
            fun sql(text: String) = value(port, "execute", "id" to id, "sql" to text, "parameters" to emptyList<Any?>())
            value(port, "script", "id" to id, "sql" to """
                CREATE TABLE messages(id INTEGER PRIMARY KEY, body TEXT UNIQUE);
                CREATE TABLE audit(message_id INTEGER);
                CREATE TRIGGER messages_audit AFTER INSERT ON messages BEGIN
                    INSERT INTO audit VALUES(new.id);
                END;
            """.trimIndent())
            sql("BEGIN IMMEDIATE")
            val committed = images.getValue("/profile/state.db").copyOf()
            sql("INSERT INTO messages(body) VALUES('rolled back')")
            assertContentEquals(committed, images.getValue("/profile/state.db"))
            assertEquals(true, (value(port, "stats", "id" to id) as Map<*, *>)["in_transaction"])
            value(port, "rollback", "id" to id)
            sql("INSERT INTO messages(body) VALUES('retained')")
            val failure = port.call(mapOf("op" to "execute", "id" to id,
                "sql" to "INSERT INTO messages(body) VALUES('retained')", "parameters" to emptyList<Any?>()))
            assertEquals("IntegrityError", (failure["error"] as Map<*, *>)["kind"])
            value(port, "close", "id" to id)
            val reopened = value(port, "connect", "path" to "/profile/state.db") as Map<*, *>
            val rows = value(port, "execute", "id" to reopened["id"], "sql" to "SELECT body, (SELECT count(*) FROM audit) FROM messages",
                "parameters" to emptyList<Any?>()) as Map<*, *>
            val row = (rows["rows"] as List<*>).single() as List<*>
            assertEquals("retained", PythonSqlite.decode(row[0]))
            assertEquals(1L, PythonSqlite.decode(row[1]))
            val next = reopened["id"]
            value(port, "execute", "id" to next, "sql" to "BEGIN IMMEDIATE", "parameters" to emptyList<Any?>())
            value(port, "execute", "id" to next, "sql" to "INSERT INTO messages(body) VALUES('closed pending')", "parameters" to emptyList<Any?>())
            val beforeClose = images.getValue("/profile/state.db").copyOf()
            value(port, "close", "id" to next)
            assertContentEquals(beforeClose, images.getValue("/profile/state.db"))
            val final = value(port, "connect", "path" to "/profile/state.db") as Map<*, *>
            val count = value(port, "execute", "id" to final["id"], "sql" to "SELECT count(*) FROM messages", "parameters" to emptyList<Any?>()) as Map<*, *>
            assertEquals(1L, PythonSqlite.decode(((count["rows"] as List<*>).single() as List<*>).single()))
        }
    }

    @Test
    fun scalarCallbacksAndTypedValuesReachTheRealEngine() {
        PythonSqlite({ null }, { _, _ -> error("memory database must not publish") }, { id, args ->
            assertEquals("identity", id)
            args.single()
        }).use { port ->
            val id = (value(port, "connect", "path" to ":memory:") as Map<*, *>)["id"]
            value(port, "function", "id" to id, "name" to "identity_content", "arity" to 1,
                "function" to "identity", "deterministic" to true)
            val bytes = byteArrayOf(0, -1, 42)
            val result = value(port, "execute", "id" to id,
                "sql" to "SELECT identity_content(:content), :number, :content IS :content",
                "parameters" to mapOf("content" to PythonSqlite.encode(bytes), "number" to PythonSqlite.encode(Long.MAX_VALUE))) as Map<*, *>
            val row = (result["rows"] as List<*>).single() as List<*>
            assertContentEquals(bytes, PythonSqlite.decode(row[0]) as ByteArray)
            assertEquals(Long.MAX_VALUE, PythonSqlite.decode(row[1]))
            assertEquals(1L, PythonSqlite.decode(row[2]))
        }
    }

    @Test
    fun nativeFileEscapesAndStaleImagePublicationAreRefused() {
        val images = mutableMapOf<String, ByteArray>()
        PythonSqlite({ images[it] }, { path, bytes -> images[path] = bytes.copyOf() }, { _, _ -> null }).use { port ->
            assertTrue("error" in port.call(mapOf("op" to "connect", "path" to "/profile/missing.db", "existing" to true)))
            assertFalse("/profile/missing.db" in images)
            val id = (value(port, "connect", "path" to "/profile/state.db") as Map<*, *>)["id"]
            val initial = images.getValue("/profile/state.db").copyOf()
            for (sql in listOf("ATTACH '/unleased/sqlite.db' AS other", "VACUUM main INTO '/unleased/sqlite.db'",
                "PRAGMA \"temp_store_directory\"='/unleased'", "PRAGMA 'temp_store'='FILE'",
                "SELECT load_extension('/unleased/extension')")) {
                val reply = port.call(mapOf("op" to "execute", "id" to id, "sql" to sql, "parameters" to emptyList<Any?>()))
                assertTrue("error" in reply, sql)
                assertContentEquals(initial, images.getValue("/profile/state.db"))
            }
            val duplicate = port.call(mapOf("op" to "connect", "path" to "/profile/state.db"))
            assertEquals("NotSupportedError", (duplicate["error"] as Map<*, *>)["kind"])
            images["/profile/state.db"] = byteArrayOf(1, 2, 3)
            val lease = value(port, "lease", "id" to id) as Map<*, *>
            assertEquals(false, lease["current"])
            assertTrue("error" in port.call(mapOf("op" to "execute", "id" to id, "sql" to "CREATE TABLE stale(x)",
                "parameters" to emptyList<Any?>())))
            assertContentEquals(byteArrayOf(1, 2, 3), images.getValue("/profile/state.db"))
        }
    }
}
