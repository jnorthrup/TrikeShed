package borg.trikeshed.miniduck.tablespace

import borg.trikeshed.miniduck.*
import borg.trikeshed.lib.*
import kotlin.test.*

class TablespaceTest {

    // --- BlockStore SPI ---

    @Test
    fun inMemoryBlockStoreRoundTrip() {
        val store = InMemoryBlockStore()
        val block = BlockRowVec.mutable()
        block.append(DocRowVec(listOf("name"), listOf("alice")))
        block.seal()

        val blockId = store.put("docs", block)
        assertNotNull(blockId)

        val loaded = store.get("docs", blockId)
        assertNotNull(loaded)
        assertEquals(BlockRowVec.State.SEALED, loaded.state)
        assertEquals(1, loaded.rowCount)
    }

    @Test
    fun blockStoreListBlocks() {
        val store = InMemoryBlockStore()
        for (i in 0 until 5) {
            val b = BlockRowVec.mutable()
            b.append(DocRowVec(listOf("id"), listOf(i)))
            b.seal()
            store.put("docs", b)
        }
        val ids = store.list("docs")
        assertEquals(5, ids.size)
    }

    @Test
    fun blockStoreReturnsNullForMissing() {
        val store = InMemoryBlockStore()
        assertNull(store.get("docs", "nonexistent"))
        assertTrue(store.list("empty_collection").isEmpty())
    }

    // --- Region ---

    @Test
    fun regionHoldsLocalityAndStore() {
        val store = InMemoryBlockStore()
        val region = Region(name = "us-east-1", store = store)
        assertEquals("us-east-1", region.name)
        assertSame(store, region.store)
    }

    // --- Tablespace ---

    @Test
    fun tablespaceMultiRegionScan() {
        val east = InMemoryBlockStore()
        val west = InMemoryBlockStore()

        // put different docs in each region
        val b1 = BlockRowVec.mutable()
        b1.append(DocRowVec(listOf("name"), listOf("alice")))
        b1.seal()
        east.put("users", b1)

        val b2 = BlockRowVec.mutable()
        b2.append(DocRowVec(listOf("name"), listOf("bob")))
        b2.seal()
        west.put("users", b2)

        val ts = Tablespace("cluster")
        ts.addRegion(Region("us-east-1", east))
        ts.addRegion(Region("us-west-2", west))

        // scan across all regions
        val cursor = ts.scan("users")
        assertEquals(2, cursor.size)
        val names = (0 until cursor.size).map { i ->
            val doc = cursor[i] as DocRowVec
            doc["name"] as CharSequence
        }.toSet()
        assertEquals(setOf("alice", "bob"), names)
    }

    @Test
    fun tablespaceSingleRegionScan() {
        val store = InMemoryBlockStore()
        val b = BlockRowVec.mutable()
        b.append(DocRowVec(listOf("x"), listOf(42)))
        b.seal()
        store.put("singles", b)

        val ts = Tablespace("solo")
        ts.addRegion(Region("local", store))

        val cursor = ts.scan("singles")
        assertEquals(1, cursor.size)
    }

    // --- JSON projection ---

    @Test
    fun jsonProjectionFromDocRowVec() {
        val doc = DocRowVec(
            listOf("name", "age"),
            listOf("alice", 30)
        )
        val block = BlockRowVec.mutable()
        block.append(doc)
        block.seal()

        val cursor = block.child
        val json = cursor.toJson()
        // should produce NDJSON: one JSON object per row
        assertTrue(json.contains("\"name\""))
        assertTrue(json.contains("\"alice\""))
        assertTrue(json.contains("\"age\""))
        assertTrue(json.contains("30"))
    }

    @Test
    fun jsonProjectionFromMultipleDocs() {
        val block = BlockRowVec.mutable()
        block.append(DocRowVec(listOf("id"), listOf(1)))
        block.append(DocRowVec(listOf("id"), listOf(2)))
        block.seal()

        val lines = block.child.toJson().lines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"id\""))
        assertTrue(lines[1].contains("\"id\""))
    }

    @Test
    fun jsonProjectionNestedDocRowVec() {
        val inner = DocRowVec(listOf("city"), listOf("NYC"))
        val outer = DocRowVec(
            listOf("name", "address"),
            listOf("alice", null),
            borg.trikeshed.lib.Series(1) { inner }
        )
        val block = BlockRowVec.mutable()
        block.append(outer)
        block.seal()

        val json = block.child.toJson()
        assertTrue(json.contains("\"city\""))
        assertTrue(json.contains("\"NYC\""))
    }

    // --- Tablespace JSON projection ---

    @Test
    fun tablespaceToJson() {
        val store = InMemoryBlockStore()
        val b = BlockRowVec.mutable()
        b.append(DocRowVec(listOf("k"), listOf("v1")))
        b.append(DocRowVec(listOf("k"), listOf("v2")))
        b.seal()
        store.put("data", b)

        val ts = Tablespace("test")
        ts.addRegion(Region("local", store))

        val json = ts.scanToJson("data")
        val lines = json.lines().filter { it.isNotBlank() }
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("v1"))
        assertTrue(lines[1].contains("v2"))
    }

    // --- Implicit schema discovery ---

    @Test
    fun implicitSchemaFromDocs() {
        val block = BlockRowVec.mutable()
        block.append(DocRowVec(listOf("name", "age"), listOf("alice", 30)))
        block.append(DocRowVec(listOf("name", "age", "email"), listOf("bob", 25, "bob@test")))
        block.seal()

        val store = InMemoryBlockStore()
        store.put("users", block)

        val ts = Tablespace("test")
        ts.addRegion(Region("local", store))

        // discover schema by scanning
        val schema = ts.discoverSchema("users")
        assertEquals("users", schema.name)
        // union of all keys across all docs
        val colNames = schema.columns.map { it.name }.toSet()
        assertTrue(colNames.contains("name"))
        assertTrue(colNames.contains("age"))
        assertTrue(colNames.contains("email"))
    }
}
