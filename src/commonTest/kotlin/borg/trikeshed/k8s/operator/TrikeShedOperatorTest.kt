package borg.trikeshed.k8s.operator

import borg.trikeshed.k8s.crd.ObjectMeta
import borg.trikeshed.k8s.crd.TrikeShedResource
import borg.trikeshed.k8s.crd.TrikeShedResourceSpec
import borg.trikeshed.operator.K8sEvent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import borg.trikeshed.ccek.ForgeSignal

class TrikeShedOperatorTest {

    @Test
    fun testOperatorReconciliationAddedEvent() = runTest {
        val operator = TrikeShedOperator(CoroutineScope(Job() + Dispatchers.Default))
        operator.start()

        val resource = TrikeShedResource(
            metadata = ObjectMeta(name = "test-resource"),
            spec = TrikeShedResourceSpec(connectToCcek = true)
        )
        val event = K8sEvent.Added(resource)

        operator.dispatchEvent(event)

        // Give it a moment to process
        delay(100)

        // Verify that a signal was recorded in CCEK via the dummy doc node
        val recording = operator.dummyDocNode.recording()
        assertTrue(recording.isNotEmpty(), "Expected at least one signal to be recorded")
        
        val lastSignal = recording.last()
        assertTrue(lastSignal is ForgeSignal.AppendBlock, "Expected AppendBlock signal")
        assertEquals("Resource added: test-resource", (lastSignal as ForgeSignal.AppendBlock).text)

        operator.stop()
    }
}
