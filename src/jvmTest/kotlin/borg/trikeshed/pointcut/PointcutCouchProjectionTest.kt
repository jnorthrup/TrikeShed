package borg.trikeshed.pointcut

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.dag.DagCoordinate
import borg.trikeshed.pointcut.PointcutBlackboardAdapter.PointcutLanding
import borg.trikeshed.pointcut.VmFacet
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import borg.trikeshed.forge.server.GraalWire
import borg.trikeshed.graal.vitals.JvmVitals
import borg.trikeshed.parse.reify
import borg.trikeshed.couch.ViewServer
import borg.trikeshed.couch.ViewDefinition
import borg.trikeshed.couch.MapFunction
import borg.trikeshed.couch.KeyExpr
import borg.trikeshed.couch.ValueExpr
import borg.trikeshed.couch.ReduceFunction

class PointcutCouchProjectionTest {

    @Test
    fun testPointcutCouchProjection() = runTest {
        val bb = ConfixBlackboard.empty()
        val adapter = PointcutBlackboardAdapter(bb)
        val store = CouchStoreFactory.inMemory()
        PointcutCouchProjection(store, adapter, backgroundScope)
        val wire = GraalWire(JvmVitals(), store, null, backgroundScope)
        suspend fun route(): Map<*, *> {
            val response = requireNotNull(wire.route("GET", "/api/graal/pointcuts", "", null))
            assertEquals(200, response.status)
            return ((reify(response.body) as Map<*, *>)["routes"] as List<*>).single() as Map<*, *>
        }

        val event = PointcutEvent(
            coordinate = "TestClass.testMethod",
            vmFacet = VmFacet.JVM,
            target = null,
            propertyName = "prop",
            newValue = "val1",
            sourcePath = "rules.py",
            line = 8,
            column = 5,
        )
        
        adapter.accept(event, true)
        
        // The collector is attached before the constructor returned (UNDISPATCHED subscribe),
        // so the landing is in flight; wait for the projection to land it rather than a fixed
        // sleep, bounded so a lost landing still fails instead of hanging.
        val docId = PointcutBlackboardAdapter.keyOf(
            event.coordinate.substringBeforeLast('.', "?"),
            event.coordinate.substringAfterLast('.'),
            PointcutBlackboardAdapter.guestSiteIdx("prop")
        )
        var doc = store.get(docId)
        var waitedMs = 0
        while (doc == null && waitedMs < 2_000) { delay(10); waitedMs += 10; doc = store.get(docId) }
        assertNotNull(doc, "Document should be present in store")
        assertEquals("rules.py", doc!!.fields.single { it.name == "sourcePath" }.value)
        assertEquals(8, doc.fields.single { it.name == "line" }.value)
        assertEquals(5, doc.fields.single { it.name == "column" }.value)
        val located = route()
        assertEquals("rules.py", located["sourcePath"])
        assertEquals(8, (located["line"] as? Number)?.toInt())
        assertEquals(5, (located["column"] as? Number)?.toInt())
        
        val initialRev = store.head.getRev(docId)
        
        // Drive second slab same site
        val event2 = PointcutEvent(
            coordinate = "TestClass.testMethod",
            seq = 1,
            vmFacet = VmFacet.JVM,
            target = null,
            propertyName = "prop",
            newValue = "val2"
        )
        adapter.accept(event2, true)
        
        var secondRev = store.head.getRev(docId)
        waitedMs = 0
        while (secondRev == initialRev && waitedMs < 2_000) { delay(10); waitedMs += 10; secondRev = store.head.getRev(docId) }
        assertNotEquals(initialRev, secondRev, "Revision should be bumped")
        assertFalse(store.get(docId)!!.fields.any { it.name in setOf("sourcePath", "line", "column") },
            "a later event with no source must not retain an earlier event's coordinates")
        val unknown = route()
        assertNull(unknown["sourcePath"])
        assertNull(unknown["line"])
        assertNull(unknown["column"])
        
        // by_typedef: the projection stores `coordinate` as a nested map; ViewServer's JsPath is flat, so key on the
        // map itself and read className out of the grouped key. execute() applies the _count reducer itself.
        val vs = ViewServer()
        val viewDef = ViewDefinition(
            ddoc = "_design/pointcut",
            viewName = "by_typedef",
            mapFn = MapFunction.Emit(
                key = KeyExpr.DocField("coordinate"),
                value = ValueExpr.Const(1)
            ),
            reduceFn = ReduceFunction.Builtin("_count")
        )

        val reduced = vs.execute(viewDef, store.all().filter { it.id.startsWith("pointcut/") })

        val rows = (0 until reduced.size).map { reduced[it] }
        val testClassCount = rows.find { (it.key as? Map<*, *>)?.get("className") == "TestClass" }
        assertNotNull(testClassCount, "expected a _count row whose coordinate.className is TestClass; keys=${rows.map { it.key }}")
        assertEquals(1, (testClassCount!!.value as Number).toInt())
    }
}
