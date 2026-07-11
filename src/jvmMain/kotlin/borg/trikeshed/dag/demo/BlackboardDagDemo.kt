package borg.trikeshed.dag.demo

import borg.trikeshed.dag.*
import borg.trikeshed.lib.size

/**
 * Live Blackboard fabric smoke — not a println catalogue.
 * create → subscribe → publish → getEvents must succeed or process exits non-zero.
 */
object BlackboardDagDemo {

    @JvmStatic
    fun main(args: Array<String>) {
        val fabric = BlackboardFabrics.create()
        val seen = mutableListOf<BlackboardEvent>()
        val sub = fabric.TODO_subscribe("ClassLoad") { seen.add(it) }

        val coord = DagCoordinate(
            className = "borg/trikeshed/forge/KanbanBoard",
            methodName = "addCard",
            bytecodeOffset = 42,
            timestamp = System.currentTimeMillis(),
            threadId = Thread.currentThread().id,
        )
        fabric.publish(
            BlackboardEvent.ClassLoad(coord, className = coord.className, classLoader = "demo"),
        )

        val lo = coord.copy(timestamp = coord.timestamp - 1)
        val hi = coord.copy(timestamp = coord.timestamp + 1)
        val slice = fabric.TODO_getEvents(lo, hi)

        check(seen.size == 1) { "subscribe delivery failed: seen=${seen.size}" }
        check(seen[0] is BlackboardEvent.ClassLoad) { "expected ClassLoad" }
        check(slice.size == 1) { "getEvents empty: size=${slice.size}" }

        sub.unsubscribe()
        fabric.publish(
            BlackboardEvent.ClassLoad(
                coord.copy(timestamp = coord.timestamp + 10, bytecodeOffset = 43),
                className = "other",
                classLoader = "demo",
            ),
        )
        check(seen.size == 1) { "unsubscribe failed: still receiving" }

        println("fabric_ok create=1 publish=2 delivered=1 slice=${slice.size}")
    }
}
