package borg.trikeshed.lcnc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 6 gate, W5.2: job.command and job.batch nodes are first-class
 * members of the contract table — Cancel/Block/Retry/Retract are now
 * reachable from a graph. Also W5.4: vm.call/vm.stats/vm.tiers.
 */
class JobCommandNodeTest {

    @Test
    fun jobCommandContractExists() {
        val c = LcncContracts.find("job.command")
        assertNotNull(c, "job.command must be in the contract table")
        assertEquals(listOf("verb", "jobId", "expectedRevision?"), c.inputs)
        assertEquals(listOf("result"), c.outputs)
        assertEquals("text", c.inputKinds["verb"])
        assertEquals("id", c.inputKinds["jobId"])
        assertEquals("json", c.outputKinds["result"])
    }

    @Test
    fun jobBatchContractExists() {
        val c = LcncContracts.find("job.batch")
        assertNotNull(c, "job.batch must be in the contract table")
        assertEquals("json", c.inputKinds["commands"])
        assertEquals("json", c.outputKinds["results"])
    }

    @Test
    fun vmCallStatsTiersContractsExist() {
        for (type in listOf("vm.call", "vm.stats", "vm.tiers")) {
            assertNotNull(LcncContracts.find(type), "$type must be in the contract table")
        }
    }

    @Test
    fun jobCommandCompatibleWithTextOutput() {
        // job.command's "verb" input is text-kind, so a text-output source
        // can feed it. The mating endpoint matches by kind, not port name.
        val job = LcncContracts.find("job.command")!!
        assertEquals("text", job.inputKinds["verb"])
        // mux.chat.content is text-kind output
        val chat = LcncContracts.find("mux.chat")!!
        assertEquals("text", chat.outputKinds["content"])
        assertTrue(chat.outputKinds["content"] == job.inputKinds["verb"],
            "text-output feeds text-input: kind-compatible")
    }
}
