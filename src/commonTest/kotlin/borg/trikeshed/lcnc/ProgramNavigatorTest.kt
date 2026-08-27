package borg.trikeshed.lcnc

import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The exact thing the goal named unproven: does the fractal dive actually
 * work — not "the code looks right", a real stack exercised to real depth.
 */
class ProgramNavigatorTest {

    private fun program(name: String) = LcncProgram(name, emptySeriesOf(), emptySeriesOf())

    private fun store(vararg programs: LcncProgram): suspend (String) -> LcncProgram? {
        val byName = programs.associateBy { it.name }
        return { name -> byName[name] }
    }

    @Test
    fun diveIntoSwapsCurrentAndRecordsHowToGetBack() = runTest {
        val root = program("root")
        val kanban = program("kanban")
        val nav = ProgramNavigator(root, store(kanban))

        assertEquals("root", nav.current.name)
        assertEquals(0, nav.depth)

        val result = nav.diveInto("kanban")
        assertEquals(ProgramNavigator.DiveResult.Ok, result)
        assertEquals("kanban", nav.current.name, "current must be the DIVED-INTO program, not root")
        assertEquals(1, nav.depth)
        assertEquals(listOf("kanban"), (0 until nav.breadcrumb.size).map { nav.breadcrumb[it] })
    }

    @Test
    fun popRestoresExactlyWhatWasThereBeforeTheDive() = runTest {
        val root = program("root")
        val kanban = program("kanban")
        val nav = ProgramNavigator(root, store(kanban))

        nav.diveInto("kanban")
        assertTrue(nav.pop())
        assertEquals("root", nav.current.name, "pop must restore root, not stay on kanban")
        assertEquals(0, nav.depth)
        assertEquals(0, nav.breadcrumb.size)
    }

    @Test
    fun popAtRootIsANoOpNotAnException() = runTest {
        val nav = ProgramNavigator(program("root"), store())
        assertFalse(nav.pop(), "nothing to pop at root")
        assertEquals("root", nav.current.name)
    }

    @Test
    fun divingIntoAMissingProgramLeavesCurrentUntouched() = runTest {
        val root = program("root")
        val nav = ProgramNavigator(root, store())
        val result = nav.diveInto("does-not-exist")
        assertEquals(ProgramNavigator.DiveResult.NotFound("does-not-exist"), result)
        assertEquals("root", nav.current.name, "a failed dive must not corrupt current")
        assertEquals(0, nav.depth, "a failed dive must not push a stack frame")
    }

    /**
     * The load-bearing case: recursion to real depth, not one hardcoded level.
     * root -> level-b -> kanban -> leaf, then climb back out ONE LEVEL AT A
     * TIME via popTo, verifying breadcrumb and current at every rung — this
     * is the fractal claim, actually exercised.
     */
    @Test
    fun recursiveDiveToThreeLevelsAndPopBackOutOneRungAtATime() = runTest {
        val root = program("root")
        val levelB = program("level-b")
        val kanban = program("kanban")
        val leaf = program("leaf")
        val nav = ProgramNavigator(root, store(levelB, kanban, leaf))

        assertEquals(ProgramNavigator.DiveResult.Ok, nav.diveInto("level-b"))
        assertEquals(ProgramNavigator.DiveResult.Ok, nav.diveInto("kanban"))
        assertEquals(ProgramNavigator.DiveResult.Ok, nav.diveInto("leaf"))

        assertEquals("leaf", nav.current.name)
        assertEquals(3, nav.depth)
        assertEquals(listOf("level-b", "kanban", "leaf"), (0 until nav.breadcrumb.size).map { nav.breadcrumb[it] })

        // popTo(N) means "the state after exactly N dives" — popTo(2) is
        // AFTER diving into level-b then kanban, i.e. current == kanban.
        assertTrue(nav.popTo(2))
        assertEquals("kanban", nav.current.name, "popTo(2): the state after exactly 2 dives is kanban")
        assertEquals(2, nav.depth)
        assertEquals(listOf("level-b", "kanban"), (0 until nav.breadcrumb.size).map { nav.breadcrumb[it] })

        // popTo(1): the state after exactly 1 dive is level-b.
        assertTrue(nav.popTo(1))
        assertEquals("level-b", nav.current.name, "popTo(1): the state after exactly 1 dive is level-b")
        assertEquals(1, nav.depth)
        assertEquals(listOf("level-b"), (0 until nav.breadcrumb.size).map { nav.breadcrumb[it] })

        // ⌂ root
        assertTrue(nav.popTo(0))
        assertEquals("root", nav.current.name)
        assertEquals(0, nav.depth)
    }

    @Test
    fun poppingPastCurrentDepthIsRejectedNotClamped() = runTest {
        val root = program("root")
        val kanban = program("kanban")
        val nav = ProgramNavigator(root, store(kanban))
        nav.diveInto("kanban")
        assertFalse(nav.popTo(5), "depth beyond the stack must be rejected, not silently clamped")
        assertEquals("kanban", nav.current.name, "a rejected pop must not disturb current")
    }

    @Test
    fun diveThenDiveAgainFromTheSameProgramIsIndependentEachTime() = runTest {
        // Diving into "kanban" from two different starting points must each
        // remember their OWN "before" state, not share one.
        val root = program("root")
        val other = program("other")
        val kanban = program("kanban")

        val navFromRoot = ProgramNavigator(root, store(kanban))
        navFromRoot.diveInto("kanban")
        assertTrue(navFromRoot.pop())
        assertEquals("root", navFromRoot.current.name)

        val navFromOther = ProgramNavigator(other, store(kanban))
        navFromOther.diveInto("kanban")
        assertTrue(navFromOther.pop())
        assertEquals("other", navFromOther.current.name, "each navigator's dive is independent")
    }
}
