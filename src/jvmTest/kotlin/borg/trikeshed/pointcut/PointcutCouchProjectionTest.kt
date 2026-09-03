package borg.trikeshed.pointcut

import borg.trikeshed.couch.CouchStoreFactory
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.dag.DagCoordinate
import borg.trikeshed.pointcut.PointcutBlackboardAdapter.PointcutLanding
import borg.trikeshed.pointcut.VmFacet
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import borg.trikeshed.couch.ViewServer
import borg.trikeshed.couch.ViewDefinition
import borg.trikeshed.couch.MapFunction
import borg.trikeshed.couch.KeyExpr
import borg.trikeshed.couch.ValueExpr
import borg.trikeshed.couch.ReduceFunction

class PointcutCouchProjectionTest {

    @Test
    fun testPointcutCouchProjection() = runBlocking {
        val bb = ConfixBlackboard.empty()
        val adapter = PointcutBlackboardAdapter(bb)
        val store = CouchStoreFactory.inMemory()
        val projection = PointcutCouchProjection(store, adapter, CoroutineScope(Dispatchers.Default))

        val event = PointcutEvent(
            coordinate = "TestClass.testMethod",
            vmFacet = VmFacet.JVM,
            target = null,
            propertyName = "prop",
            newValue = "val1"
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
