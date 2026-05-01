package borg.trikeshed.miniduck

import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * RED test for U2: `child: Series<MiniRowVec>?` lazy-loading base class.
 *
 * Currently each RowVec subclass implements its own caching logic.
 * The goal is to extract a shared `LazyChildRowVec` base class with a
 * `loadChild(cached, factory)` helper so the pattern appears in one place.
 */
class LazyChildRowVecTest {

    // A minimal concrete subclass to exercise the shared helper
   class TestLazyRowVec(
        val loader: () -> Series<MiniRowVec>?,
    ) : LazyChildRowVec() {
       var cached: Series<MiniRowVec>? = null

        override val size: Int get() = 0
        override fun get(index: Int): Any? =
            throw IndexOutOfBoundsException("TestLazyRowVec is a shell")

        // Expose loadChild for test — pattern: cached var + loadChild helper
        override val child: Series<MiniRowVec>?
            get() = loadChild(cached) { loader()?.also { cached = it } }
    }

    @Test
    fun `loadChild returns null when factory returns null`() {
        val row = TestLazyRowVec { null }
        assertEquals(null, row.child)
    }

    @Test
    fun `loadChild returns the same Series on every access (caching)`() {
        var callCount = 0
        val factory: () -> Series<MiniRowVec>? = {
            callCount++
            1 j { _: Int -> DocRowVec(emptyList(), emptyList()) }
        }
        val row = TestLazyRowVec(factory)

        val first = row.child
        val second = row.child

        assert(first != null)
        assert(second != null)
        assertSame(first, second)  // cached
        assertEquals(1, callCount)  // factory called exactly once
    }

    @Test
    fun `loadChild calls factory on every access when factory returns null`() {
        // ViewRowVec behavior: null is a terminal state but subsequent
        // accesses still invoke the factory (loadedChild ?: ... matches null)
        var invocations = 0
        val row = TestLazyRowVec { invocations++; null }
        row.child // trigger first load
        row.child // second access
        assertEquals(2, invocations)  // factory called twice (null is not cached)
    }

    @Test
    fun `child is accessible on concrete subclasses`() {
        val doc = DocRowVec(listOf("a"), listOf(1))
        val row = TestLazyRowVec { 1 j { doc } }
        val child = row.child
        assert(child != null)
        assertEquals(1, child!!.a)
    }
}
