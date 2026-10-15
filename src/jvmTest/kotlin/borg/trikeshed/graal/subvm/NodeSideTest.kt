package borg.trikeshed.graal.subvm

import borg.trikeshed.vm.*
import org.graalvm.polyglot.Context
import kotlin.test.*

class NodeSideTest {
    @Test
    fun nonFiniteResultsAndHostArgumentsRemainOpaque() {
        Context.create("js").use { context ->
            // Node's stream/event boundary is captured; the actual backend handles both messages.
            context.eval("js", """
                const frames = [];
                let receive;
                const process = { stdout: { write: text => frames.push(text) }, stderr: {} };
                const require = name => {
                    if (name !== 'readline') throw new Error('unexpected module: ' + name);
                    return {createInterface: () => ({on: (event, handler) => { receive = handler; }})};
                };
            """.trimIndent())
            context.eval("js", ProcessIsolate.NodeSide.SCRIPT)
            fun submit(source: String) {
                val message = SubVmProtocol.encode(Teleported.obj("id" to 7, "op" to "eval", "source" to source))
                context.getBindings("js").putMember("request", message)
                context.eval("js", "receive(request)")
            }
            val expected = Teleported.Arr(listOf("NaN", "Infinity", "-Infinity").map { Teleported.Opaque("number:$it") })
            submit("[NaN, Infinity, -Infinity]")
            val result = SubVmProtocol.decode(context.eval("js", "frames[0]").asString())
            assertEquals(true, result.bool("ok"))
            assertEquals(expected, result.field("value"))

            submit("hostAsync('numbers', NaN, Infinity, -Infinity)")
            val call = SubVmProtocol.decode(context.eval("js", "frames[1]").asString())
            assertEquals("host", call.str("op"))
            assertEquals("numbers", call.str("name"))
            assertEquals(expected, call.field("args"))
            context.eval("js", "receive(JSON.stringify({id:1, value:42}))")
            val reply = SubVmProtocol.decode(context.eval("js", "frames[2]").asString())
            assertEquals(Teleported.Num(42), reply.field("value"))
        }
    }
}
