package borg.trikeshed.memory.ace

import borg.trikeshed.lcnc.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.reify
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class AceContextNodesTest {
    @Test fun optionalFrameWiresRetainPayloadIdentity(): Unit = runBlocking {
        val payloads = s_["Read source receipts", "Keep reviewed evidence", "Thread local curation", "First turn"]
        val ports = s_["toolsSystem", "playbook", "envelope", "tail"]
        val assemble = LcncNode("assemble", "context.assemble", _m["model" j "local", "effort" j "medium"])
        val nodes = (payloads.size + 1) j { index: Int ->
            if (index == payloads.size) assemble
            else LcncNode("source-$index", "text.value", _m["value" j payloads[index]])
        }
        val wires = (0 until ports.size).toSeries() α { index ->
            LcncWire("source-$index", "value", "assemble", "${ports[index]}?")
        }
        val program = LcncProgram("context-frames", nodes, wires)
        val runner = LcncRunner(PureNodes.registry { 123L } + AceContextNodes.registry())
        val output = runner.runAll(program).getValue("assemble")
        val expected = AceContextProgram.assemble(AceContextProgram.preset(
            payloads[0].encodeToByteArray(), payloads[1].encodeToByteArray(),
            payloads[2].encodeToByteArray(), payloads[3].encodeToByteArray(),
        ), AceContextProgram.Config("local", emptySeriesOf(), "medium"))
        assertEquals(expected[expected.size - 1].cid.value, output["chainHead"])

        val normalized = program.copy(wires = wires α { it.copy(toPort = it.toPort.removeSuffix("?")) })
        assertEquals(output, runner.runAll(normalized).getValue("assemble"))

        val changed = program.copy(nodes = nodes α {
            if (it.id == "source-3") it.copy(params = _m["value" j "Second turn"]) else it
        })
        val updated = runner.runAll(changed).getValue("assemble")
        assertNotEquals(output["chainHead"], updated["chainHead"])
        val priorChain = reify(output["chain"] as String) as List<*>
        val nextChain = reify(updated["chain"] as String) as List<*>
        assertEquals(priorChain.dropLast(1), nextChain.dropLast(1))
    }

    @Test fun normalizedFrameNameTakesPrecedenceOverOptionalAlias(): Unit = runBlocking {
        val runner = AceContextNodes.registry().getValue("context.assemble")
        val node = LcncNode("assemble", "context.assemble")
        val primary = runner.run(node, _m["playbook" j "Retained policy"])
        assertEquals(primary, runner.run(node, _m["playbook" j "Retained policy", "playbook?" j "Other policy"]))
        val empty = runner.run(node, _m.get())
        assertEquals(empty, runner.run(node, _m["playbook" j null, "playbook?" j "Other policy"]))
    }

    @Test fun frameParametersRemainDefaultsAndNullWiresMaskThem(): Unit = runBlocking {
        val runner = AceContextNodes.registry().getValue("context.assemble")
        val bare = LcncNode("assemble", "context.assemble")
        val configured = bare.copy(params = _m["playbook" j "Plain default", "in:playbook" j "Port default"])
        assertEquals(runner.run(bare, _m["playbook" j "Port default"]), runner.run(configured, _m.get()))
        assertEquals(runner.run(bare, _m["playbook" j "Wired policy"]),
            runner.run(configured, _m["playbook?" j "Wired policy"]))
        assertEquals(runner.run(bare, _m.get()), runner.run(configured, _m["playbook?" j null]))
        assertEquals(runner.run(bare, _m.get()),
            runner.run(configured, _m["playbook" j null, "playbook?" j "Wired policy"]))
    }
}
