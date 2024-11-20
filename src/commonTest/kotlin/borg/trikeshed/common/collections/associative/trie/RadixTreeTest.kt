package borg.trikeshed.common.collections.associative.trie

import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RadixTreeTest {
    val banana = "banana".toSeries()
    val banner = "banner".toSeries()
    val ban = "ban".toSeries()
    val banshee = "banshee".toSeries()

    @Test
    fun testKeys() {
        val tree = RadixTree<Char>()
        
        // Add items one at a time with validation
        val items = listOf(banshee, ban, banana, banner, "b1bomber".toSeries())
        items.forEach { item -> 
            if (item.size == 0) return@forEach
            try {
                tree + item
            } catch (e: Exception) {
                println("Failed to add item: $e")
            }
        }
        
        val keys = try {
            tree.keys()
        } catch (e: Exception) {
            println("Failed to get keys: $e")
            emptyList()
        }
        
        // Convert to strings with proper error handling
        val keyStrings = keys.mapNotNull { key -> 
            try {
                if (key.size == 0) null
                else key.asString()
            } catch (e: Exception) {
                println("Failed to convert key: $e")
                null
            }
        }.toSet()
        
        assertEquals(5, keyStrings.size, "Should have 5 keys")
        val expectedKeys = setOf("banshee", "ban", "banana", "banner", "b1bomber")
        assertTrue(keyStrings.containsAll(expectedKeys), 
                  "Missing keys. Expected: $expectedKeys, Got: $keyStrings")
    }

    @Test
    fun insertAndVerifyNodes() {
        // Insert the values "banana", "banner", "ban", "banshee" into the PrefixTree

        val tree = RadixTree<Char>()
        tree + banana
        tree + banner
        tree + ban
        tree + banshee
        debug { }
        // Verify the nodes
        tree.root?.let { root ->
            assert(root.key.commonPrefixWith("ban".toSeries()).size == root.key.size) { "Expected root key to be 'ban'" }
            assert(root.term) { "Root should be terminal" }
            assert(root.children.size == 3) { "Root should have 3 children" }
            
            val children = root.children
            assert(children[0].key.asString() == "ana") { "First child should be 'ana'" }
            assert(children[0].term) { "First child should be terminal" }
            assert(children[0].children.isEmpty()) { "First child should have no children" }
            
            assert(children[1].key.asString() == "ner") { "Second child should be 'ner'" }
            assert(children[1].term) { "Second child should be terminal" }
            assert(children[1].children.isEmpty()) { "Second child should have no children" }
            
            assert(children[2].key.asString() == "shee") { "Third child should be 'shee'" }
            assert(children[2].term) { "Third child should be terminal" }
            assert(children[2].children.isEmpty()) { "Third child should have no children" }
            }
        }
    }
}
