package narchy.spacegraph.navigation

import borg.trikeshed.lib.*
import narchy.spacegraph.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Expected values are upstream test/descent.test.ts, copied by hand.


class DescentTest {
    private val nodes = _m[
        "session" j DescentNode("session", "session", _l["turn-1", "turn-2", "session-child", "ghost"]),
        "turn-1" j DescentNode("turn-1", "turn", _l["phase-1"]),
        "turn-2" j DescentNode("turn-2", "turn"),
        "phase-1" j DescentNode("phase-1", "phase", _l["tool-1"]),
        "tool-1" j DescentNode("tool-1", "tool", _l["part-1"]),
        "part-1" j DescentNode("part-1", "part"),
        "session-child" j DescentNode("session-child", "session", _l["turn-2"]),
        "time" j DescentNode("time", "time", _l["checkpoint"]),
        "checkpoint" j DescentNode("checkpoint", "checkpoint", _l["session"]),
    ]
    private val context = DescentContext(nodes)
    private val chain = kindDescent("pipeline", _l["session", "turn", "phase", "tool", "part"])

    // test/descent.test.ts: 'follows a host-defined kind ladder and drops ids outside the model'
    @Test fun followsHostKindLadderAndDropsIdsOutsideModel() {
        val registry = DescentRegistry(_l[chain])
        assertEquals(_l["turn-1", "turn-2"], registry.childrenOf(nodes["session"]!!, context))
        assertEquals(emptyList(), registry.childrenOf(nodes["part-1"]!!, context))
    }

    // test/descent.test.ts: 'finds a root-inclusive path, or undefined when no policy connects the nodes'
    @Test fun findsRootInclusivePathOrNullWhenNoPolicyConnects() {
        val registry = DescentRegistry(_l[chain])
        assertEquals(
            _l["session", "turn-1", "phase-1", "tool-1", "part-1"],
            registry.path("session", "part-1", context),
        )
        assertNull(registry.path("session", "session-child", context))
        assertNull(registry.path("session", "ghost", context))
    }

    // test/descent.test.ts: 'composes host policies for time and delegation without library vocabulary'
    @Test fun composesHostPoliciesForTimeAndDelegation() {
        val registry = DescentRegistry(
            _l[
                DescentPolicy("time", _l["time"]) { node, _ ->
                    node.children?.filter { nodes[it]?.kind == "checkpoint" }
                },
                DescentPolicy("checkpoint", _l["checkpoint"]) { node, _ -> node.children },
                chain,
            ],
        )
        assertEquals(_l["time", "checkpoint", "pipeline"], registry.ids)
        assertEquals(_l["checkpoint"], registry.childrenOf(nodes["time"]!!, context))
        assertEquals(
            _l["time", "checkpoint", "session", "turn-1", "phase-1", "tool-1", "part-1"],
            registry.path("time", "part-1", context),
        )
    }

    // test/descent.test.ts: 'lets the first matching policy win'
    @Test fun letsFirstMatchingPolicyWin() {
        val registry = DescentRegistry(
            _l[
                DescentPolicy("delegate", _l["session"]) { node, _ ->
                    node.children?.filter { nodes[it]?.kind == "session" }
                },
                chain,
            ],
        )
        assertEquals(_l["session-child"], registry.childrenOf(nodes["session"]!!, context))
    }

    // test/descent.test.ts: 'allows a host to register a policy after construction'
    @Test fun allowsRegisteringPolicyAfterConstruction() {
        val registry = DescentRegistry()
        assertEquals(emptyList(), registry.childrenOf(nodes["time"]!!, context))
        registry.register(DescentPolicy("checkpoint", _l["time"]) { node, _ -> node.children })
        assertEquals(_l["checkpoint"], registry.childrenOf(nodes["time"]!!, context))
    }
}
