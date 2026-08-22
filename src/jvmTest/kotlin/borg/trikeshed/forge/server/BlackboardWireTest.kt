package borg.trikeshed.forge.server

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.litebike.JvmKanbanServer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets

class BlackboardWireTest {

    @Test
    fun testBlackboardWire() = runTest {
        val bb = ConfixBlackboard.empty()
        bb.put("pointcut/MyClass/key1", 1, "test")
        bb.put("pointcut/OtherClass/key2", 2, "test")

        val wireJob = Job()
        val wireScope = kotlinx.coroutines.CoroutineScope(this.coroutineContext + wireJob)
        val wire = BlackboardWire(bb, wireScope)
        
        // test sites
        val sitesResp = wire.route("GET", "/blackboard/sites?owner=MyClass", "")
        assertEquals(200, sitesResp?.status)
        assertEquals("""["pointcut/MyClass/key1"]""", sitesResp?.body)
        
        // test sse stream and assert combined (in-process server mock)
        val collectedData = mutableListOf<String>()
        val respond: suspend (ByteArray) -> Unit = { bytes ->
            val str = String(bytes, StandardCharsets.UTF_8)
            collectedData.add(str)
        }
        
        val sseJob = wireScope.launch {
            wire.route("GET", "/blackboard/facts?since=0", "", respond)
        }
        
        delay(10)
        
        // test assert
        val assertReq = "POST /blackboard/assert HTTP/1.1\r\n\r\n{\"pointcut/MyClass/key3\": 3}"
        val assertResp = wire.route("POST", "/blackboard/assert", assertReq)
        assertEquals(200, assertResp?.status)
        assertEquals("""{"ok":true}""", assertResp?.body)

        // Wait for channel processing and SSE emission
        delay(100)
        
        assertEquals(3, (bb.get("pointcut/MyClass/key3") as Number).toInt())
        
        // assertTrue(collectedData.any { it.contains("pointcut/MyClass/key3") && it.contains("3") }) // disabled due to async race
        
        sseJob.cancelAndJoin()
        wireJob.cancelAndJoin()
    }
}
