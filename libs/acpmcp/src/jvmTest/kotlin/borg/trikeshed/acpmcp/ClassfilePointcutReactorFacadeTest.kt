package borg.trikeshed.acpmcp

import borg.trikeshed.lib.α
import borg.trikeshed.lib.view
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClassfilePointcutReactorFacadeTest {
    @Test
    fun `actual facade routes JVM classfile harness pointcuts into reactor`() = runTest {
        val reactor = PointcutReactorElement()
        reactor.open()

        val report = classfilePointcutReactorFacade().routeJvmValuePointcuts(reactor)
        val events = reactor.events()
        val opcodes = events.α { it.jvmOpcode }.view.toSet()
        val phases = events.α { it.phase }.view.toSet()

        assertEquals(report.routed, events.a)
        assertTrue(events.a > 0, "facade should route real classfile harness pointcut events")
        assertTrue(
            setOf("GETFIELD", "PUTFIELD", "GETSTATIC", "PUTSTATIC", "IDIV", "IREM").all { it in opcodes },
            "expected classfile value opcodes in routed reactor events, saw $opcodes",
        )
        assertEquals(setOf(PointcutRoutePhase.BEFORE, PointcutRoutePhase.AFTER), phases)
        assertTrue(events.view.all { it.sourceLanguage == "jvm" })
        assertTrue(events.view.all { it.methodIdx >= 0 && it.templateIdx >= 0 })
        assertTrue(events.view.groupBy { it.jvmOpcode to it.addr }.values.any { site ->
            site.map { it.phase }.toSet() == setOf(PointcutRoutePhase.BEFORE, PointcutRoutePhase.AFTER)
        })
        assertEquals(opcodes, report.opcodes.view.toSet())
    }
}
