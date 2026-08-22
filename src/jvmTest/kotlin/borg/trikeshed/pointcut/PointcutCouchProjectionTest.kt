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

class PointcutCouchProjectionTest {

    @Test
    fun testPointcutCouchProjection() = runBlocking {
        val bb = ConfixBlackboard.empty()
        val adapter = PointcutBlackboardAdapter(bb)
        val store = CouchStoreFactory.inMemory()
        val projection = PointcutCouchProjection(store, adapter, CoroutineScope(Dispatchers.Default))

        val event = PointcutEvent(
            timestamp = 1000L,
            coordinate = "TestClass.testMethod",
            vmFacet = VmFacet.JVM,
            target = null,
            propertyName = "prop",
            newValue = "val1"
        )
        
        adapter.accept(event, true)
        
        // Wait a short time for the flow to process
        delay(100)
        
        // Assert the document exists
        val docId = PointcutBlackboardAdapter.keyOf(
            event.coordinate.substringBeforeLast('.', "?"),
            event.coordinate.substringAfterLast('.'),
            PointcutBlackboardAdapter.guestSiteIdx("prop")
        )
        var doc = store.get(docId)
        assertNotNull(doc, "Document should be present in store")
        
        val initialRev = store.head.getRev(docId)
        
        // Drive second slab same site
        val event2 = PointcutEvent(
            timestamp = 1005L,
            coordinate = "TestClass.testMethod",
            vmFacet = VmFacet.JVM,
            target = null,
            propertyName = "prop",
            newValue = "val2"
        )
        adapter.accept(event2, true)
        
        delay(100)
        
        val secondRev = store.head.getRev(docId)
        assertNotEquals(initialRev, secondRev, "Revision should be bumped")
        
        // Use ViewServer to execute map/reduce manually since memory store doesn't inherently trigger it on string AST query out-of-the-box easily
        val vs = ViewServer()
        val viewDef = ViewDefinition(
            ddoc = "_design/pointcut",
            viewName = "by_typedef",
            mapFn = MapFunction.Emit(
                key = KeyExpr.DocFieldPath("coordinate", "className"),
                value = ValueExpr.Const(1)
            ),
            reduceFn = borg.trikeshed.viewserver.ReducerIdentity.COUNT
        )
        
        val result = vs.execute(viewDef, store.all().filter { it.id.startsWith("pointcut/") })
        
        val grouped = vs.group(result)
        val reduced = vs.reduce(viewDef, grouped)
        
        val testClassCount = reduced.find { it.key == "TestClass" }
        assertNotNull(testClassCount)
        assertEquals(1, testClassCount!!.value)
    }
}
