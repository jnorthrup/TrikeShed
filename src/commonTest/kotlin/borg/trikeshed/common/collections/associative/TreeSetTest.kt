package borg.trikeshed.common.collections.associative

import kotlin.test.Test
import kotlin.test.assertTrue
class TreeSetTest{}
@Test
fun testTreeSet() {
    val set = TreeSet<Int>()
    set.add(1)
    set.add(2)
    set.add(3)

    assertTrue(set.contains(1))
    assertTrue(set.contains(2))
    assertTrue(set.contains(3))
}

