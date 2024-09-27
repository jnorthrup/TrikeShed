package borg.trikeshed.common.collections.associative

import borg.trikeshed.common.collections.ArraySet
import borg.trikeshed.common.collections.HashSet
import kotlin.test.Test

class TestAssrtaySet{
    @Test
    fun testAssrtaySet() {
        val arraySet = ArraySet<Int>()
        arraySet.add(10)
        arraySet.add(20)
        arraySet.add(30)
        println("ArraySet contains 20: ${arraySet.contains(20)}") // Output: true
        arraySet.remove(20)
        println("ArraySet contains 20 after removal: ${arraySet.contains(20)}") // Output: false

        val hashSet = HashSet<String>()
        hashSet.add("apple")
        hashSet.add("banana")
        hashSet.add("cherry")
        println("HashSet contains 'banana': ${hashSet.contains("banana")}") // Output: true
        hashSet.remove("banana")
        println("HashSet contains 'banana' after removal: ${hashSet.contains("banana")}") // Output: false
    }
}