package borg.trikeshed.collections.associative

import kotlin.test.*

class ItemTest {

    // -- Item construction --

    @Test
    fun itemMapConstruction() {
        val item = itemMapOf(
            "name" to Item.Str("alice"),
            "age" to Item.Num(30),
        )
        assertEquals(2, item.size)
        assertEquals("alice", item["name"]?.strValue)
        assertEquals(30L, item["age"]?.longValue)
    }

    @Test
    fun itemNestedMap() {
        val item = itemMapOf(
            "user" to itemMapOf(
                "name" to Item.Str("bob"),
                "active" to Item.Bool(true),
            )
        )
        val user = item["user"] as? Item.Map
        assertNotNull(user)
        assertEquals("bob", user["name"]?.strValue)
        assertEquals(true, user["active"]?.boolValue)
    }

    @Test
    fun itemArray() {
        val arr = itemArrayOf(Item.Str("a"), Item.Str("b"), Item.Str("c"))
        assertEquals(3, arr.size)
        assertEquals("a", arr[0].strValue)
        assertEquals("c", arr[2].strValue)
    }

    @Test
    fun itemNil() {
        val item = itemMapOf("x" to Item.Nil)
        assertNull(item["x"]?.toAny())
    }

    // -- toItem / toAny round-trip --

    @Test
    fun anyToItemRoundTrip() {
        val original: Any = mapOf(
            "name" to "alice",
            "age" to 30L,
            "active" to true,
            "tags" to listOf("dev", "kotlin"),
        )
        val item = original.toItem()
        val back = item.toAny() as Map<*, *>
        assertEquals("alice", back["name"])
        assertEquals(30L, back["age"])
        assertEquals(true, back["active"])
        assertEquals(listOf("dev", "kotlin"), back["tags"])
    }

    // -- CBOR round-trip: scalars --

    @Test
    fun cborInt() {
        val values = listOf(0L, 1L, 23L, 24L, 255L, 256L, 65535L, 65536L, Int.MAX_VALUE.toLong(), -1L, -100L)
        for (v in values) {
            val bytes = Cbor.encode(Item.Num(v))
            val decoded = Cbor.decode(bytes)
            assertEquals(v, (decoded as Item.Num).value, "Failed for $v")
        }
    }

    @Test
    fun cborString() {
        val values = listOf("", "hello", "a".repeat(24), "a".repeat(256), "unicode: \u00e9\u00e0\u00fc")
        for (v in values) {
            val bytes = Cbor.encode(Item.Str(v))
            val decoded = Cbor.decode(bytes)
            assertEquals(v, (decoded as Item.Str).value, "Failed for string of length ${v.length}")
        }
    }

    @Test
    fun cborBool() {
        val t = Cbor.decode(Cbor.encode(Item.Bool(true)))
        val f = Cbor.decode(Cbor.encode(Item.Bool(false)))
        assertEquals(true, (t as Item.Bool).value)
        assertEquals(false, (f as Item.Bool).value)
    }

    @Test
    fun cborNull() {
        val decoded = Cbor.decode(Cbor.encode(Item.Nil))
        assertTrue(decoded is Item.Nil)
    }

    @Test
    fun cborFloat() {
        val values = listOf(0.0, 1.0, 3.14, -1.0e10, Double.MIN_VALUE, Double.MAX_VALUE)
        for (v in values) {
            val decoded = Cbor.decode(Cbor.encode(Item.Flt(v)))
            assertEquals(v, (decoded as Item.Flt).value, 0.0, "Failed for $v")
        }
    }

    @Test
    fun cborBytes() {
        val original = byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte())
        val decoded = Cbor.decode(Cbor.encode(Item.Bin(original)))
        assertTrue(decoded is Item.Bin)
        assertTrue(original.contentEquals(decoded.value))
    }

    // -- CBOR round-trip: structures --

    @Test
    fun cborArray() {
        val arr = itemArrayOf(
            Item.Str("x"),
            Item.Num(42),
            Item.Bool(true),
            Item.Nil,
        )
        val decoded = Cbor.decode(Cbor.encode(arr)) as Item.Arr
        assertEquals(4, decoded.size)
        assertEquals("x", decoded[0].strValue)
        assertEquals(42L, decoded[1].longValue)
        assertEquals(true, decoded[2].boolValue)
        assertTrue(decoded[3] is Item.Nil)
    }

    @Test
    fun cborMap() {
        val map = itemMapOf(
            "name" to Item.Str("alice"),
            "age" to Item.Num(30),
            "active" to Item.Bool(true),
        )
        val decoded = Cbor.decode(Cbor.encode(map)) as Item.Map
        assertEquals(3, decoded.size)
        assertEquals("alice", decoded["name"]?.strValue)
        assertEquals(30L, decoded["age"]?.longValue)
        assertEquals(true, decoded["active"]?.boolValue)
    }

    @Test
    fun cborNestedStructure() {
        val doc = itemMapOf(
            "user" to itemMapOf(
                "name" to Item.Str("bob"),
                "roles" to itemArrayOf(Item.Str("admin"), Item.Str("dev")),
            ),
            "count" to Item.Num(99),
        )
        val bytes = Cbor.encode(doc)
        val decoded = Cbor.decode(bytes) as Item.Map

        val user = decoded["user"] as Item.Map
        assertEquals("bob", user["name"]?.strValue)
        val roles = user["roles"] as Item.Arr
        assertEquals(2, roles.size)
        assertEquals("admin", roles[0].strValue)
        assertEquals(99L, decoded["count"]?.longValue)
    }

    @Test
    fun cborTag() {
        val tagged = Item.Tag(1u, Item.Num(1363896240)) // epoch timestamp
        val decoded = Cbor.decode(Cbor.encode(tagged)) as Item.Tag
        assertEquals(1u, decoded.tag)
        assertEquals(1363896240L, (decoded.item as Item.Num).value)
    }

    // -- CBOR round-trip: full document --

    @Test
    fun cborFullDocumentRoundTrip() {
        val original = mapOf(
            "_id" to "doc-001",
            "type" to "user",
            "name" to "alice",
            "age" to 30,
            "active" to true,
            "tags" to listOf("dev", "kotlin"),
            "address" to mapOf(
                "city" to "NYC",
                "zip" to "10001",
            ),
        )
        val item = original.toItem()
        val bytes = Cbor.encode(item)

        // verify CBOR is compact
        assertTrue(bytes.size < 120, "CBOR should be compact, got ${bytes.size} bytes")

        val decoded = Cbor.decode(bytes)
        val back = decoded.toAny() as Map<*, *>

        assertEquals("doc-001", back["_id"])
        assertEquals("alice", back["name"])
        assertEquals(30L, back["age"])
        assertEquals(true, back["active"])
        assertEquals(listOf("dev", "kotlin"), back["tags"])
        val addr = back["address"] as Map<*, *>
        assertEquals("NYC", addr["city"])
        assertEquals("10001", addr["zip"])
    }

    // -- CBOR size advantage --

    @Test
    fun cborMoreCompactThanJson() {
        val doc = itemMapOf(
            "name" to Item.Str("alice"),
            "age" to Item.Num(30),
            "active" to Item.Bool(true),
        )
        val cborBytes = Cbor.encode(doc)
        val jsonText = """{"name":"alice","age":30,"active":true}"""
        assertTrue(cborBytes.size < jsonText.length, "CBOR (${cborBytes.size}) should be shorter than JSON (${jsonText.length})")
    }
}
