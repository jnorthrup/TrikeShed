package borg.trikeshed.graal.subvm

import borg.trikeshed.lib.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class PythonSqliteInterruptTest {
    @Test fun interruptReachesAnExecutingStatementWithoutWaitingForTheCallMonitor() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val acknowledged = CountDownLatch(1)
        val cleanup = AtomicBoolean(false)
        val sqlResult = AtomicReference<Map<String, Any?>>()
        val interruptResult = AtomicReference<Map<String, Any?>>()
        val sqlite = PythonSqlite({ null }, { _, _, _ -> true }, { _, _ ->
            entered.countDown()
            check(release.await(20, TimeUnit.SECONDS)) { "SQLite function did not resume" }
            check(!cleanup.get()) { "test cleanup" }
            0
        })
        val connection = sqlite.call(_m["op" j "connect", "path" j ":memory:"])
        assertNull(connection["error"], connection.toString())
        val id = ((connection["value"] as Map<*, *>)["id"] as Number).toInt()
        val installed = sqlite.call(_m["op" j "function", "id" j id, "name" j "hold", "arity" j 1, "function" j "hold"])
        assertNull(installed["error"], installed.toString())
        val query = Thread {
            sqlResult.set(sqlite.call(_m["op" j "execute", "id" j id, "sql" j
                "WITH RECURSIVE t(x) AS (SELECT 0 UNION ALL SELECT x+1 FROM t WHERE x<100000000) SELECT sum(hold(x)) FROM t"]))
        }
        val cancel = Thread {
            interruptResult.set(sqlite.call(_m["op" j "interrupt", "id" j id]))
            acknowledged.countDown()
        }
        try {
            query.start()
            assertTrue(entered.await(20, TimeUnit.SECONDS), "SQLite did not enter the running statement")
            cancel.start()
            assertTrue(acknowledged.await(5, TimeUnit.SECONDS), "interrupt waited for the executing call")
            assertNull(interruptResult.get()["error"], interruptResult.get().toString())
            release.countDown()
            query.join(20_000)
            assertFalse(query.isAlive, "interrupted query did not finish")
            val failure = assertNotNull(sqlResult.get()["error"] as? Map<*, *>)
            assertEquals(9, (failure["code"] as Number).toInt())
            assertNull(sqlite.call(_m["op" j "close", "id" j id])["error"])
            assertNotNull(sqlite.call(_m["op" j "interrupt", "id" j id])["error"])
        } finally {
            cleanup.set(true)
            release.countDown()
            query.join(20_000)
            if (cancel.state != Thread.State.NEW) cancel.join(20_000)
            sqlite.close()
        }
    }
}
