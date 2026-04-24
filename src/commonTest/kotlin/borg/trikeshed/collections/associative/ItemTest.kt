     1|package borg.trikeshed.collections.associative
     2|
     3|import kotlin.test.*
     4|
     5|class ItemTest {
     6|
     7|    // -- Item construction --
     8|
     9|    @Test
    10|    fun itemMapConstruction() {
    11|        val item = itemMapOf(
    12|            "name" to Item.Str("alice"),
    13|            "age" to Item.Num(30),
    14|        )
    15|        assertEquals(2, item.size)
    16|        assertEquals("alice", item["name"]?.strValue)
    17|        assertEquals(30L, item["age"]?.longValue)
    18|    }
    19|
    20|    @Test
    21|    fun itemNestedMap() {
    22|        val item = itemMapOf(
    23|            "user" to itemMapOf(
    24|                "name" to Item.Str("bob"),
    25|                "active" to Item.Bool(true),
    26|            )
    27|        )
    28|        val user = item["user"] as? Item.Map
    29|        assertNotNull(user)
    30|        assertEquals("bob", user["name"]?.strValue)
    31|        assertEquals(true, user["active"]?.boolValue)
    32|    }
    33|
    34|    @Test
    35|    fun itemArray() {
    36|        val arr = itemArrayOf(Item.Str("a"), Item.Str("b"), Item.Str("c"))
    37|        assertEquals(3, arr.size)
    38|        assertEquals("a", arr[0].strValue)
    39|        assertEquals("c", arr[2].strValue)
    40|    }
    41|
    42|    @Test
    43|    fun itemNil() {
    44|        val item = itemMapOf("x" to Item.Nil)
    45|        assertNull(item["x"]?.toAny())
    46|    }
    47|
    48|    // -- toItem / toAny round-trip --
    49|
    50|    @Test
    51|    fun anyToItemRoundTrip() {
    52|        val original: Any = mapOf(
    53|            "name" to "alice",
    54|            "age" to 30L,
    55|            "active" to true,
    56|            "tags" to listOf("dev", "kotlin"),
    57|        )
    58|        val item = original.toItem()
    59|        val back = item.toAny() as Map<*, *>
    60|        assertEquals("alice", back["name"])
    61|        assertEquals(30L, back["age"])
    62|        assertEquals(true, back["active"])
    63|        assertEquals(listOf("dev", "kotlin"), back["tags"])
    64|    }
    65|
    66|    // -- CBOR round-trip: scalars --
    67|
    68|    @Test
    69|    fun cborInt() {
    70|        val values = listOf(0L, 1L, 23L, 24L, 255L, 256L, 65535L, 65536L, Int.MAX_VALUE.toLong(), -1L, -100L)
    71|        for (v in values) {
    72|            val bytes = Cbor.encode(Item.Num(v))
    73|            val decoded = Cbor.decode(bytes)
    74|            assertEquals(v, (decoded as Item.Num).value, "Failed for $v")
    75|        }
    76|    }
    77|
    78|    @Test
    79|    fun cborString() {
    80|        val values = listOf("", "hello", "a".repeat(24), "a".repeat(256), "unicode: \u00e9\u00e0\u00fc")
    81|        for (v in values) {
    82|            val bytes = Cbor.encode(Item.Str(v))
    83|            val decoded = Cbor.decode(bytes)
    84|            assertEquals(v, (decoded as Item.Str).value, "Failed for string of length ${v.length}")
    85|        }
    86|    }
    87|
    88|    @Test
    89|    fun cborBool() {
    90|        val t = Cbor.decode(Cbor.encode(Item.Bool(true)))
    91|        val f = Cbor.decode(Cbor.encode(Item.Bool(false)))
    92|        assertEquals(true, (t as Item.Bool).value)
    93|        assertEquals(false, (f as Item.Bool).value)
    94|    }
    95|
    96|    @Test
    97|    fun cborNull() {
    98|        val decoded = Cbor.decode(Cbor.encode(Item.Nil))
    99|        assertTrue(decoded is Item.Nil)
   100|    }
   101|
   102|    @Test
   103|    fun cborFloat() {
   104|        val values = listOf(0.0, 1.0, 3.14, -1.0e10, Double.MIN_VALUE, Double.MAX_VALUE)
   105|        for (v in values) {
   106|            val decoded = Cbor.decode(Cbor.encode(Item.Flt(v)))
   107|            assertEquals(v, (decoded as Item.Flt).value, 0.0, "Failed for $v")
   108|        }
   109|    }
   110|
   111|    @Test
   112|    fun cborBytes() {
   113|        val original = byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte())
   114|        val decoded = Cbor.decode(Cbor.encode(Item.Bin(original)))
   115|        assertTrue(decoded is Item.Bin)
   116|        assertTrue(original.contentEquals(decoded.value))
   117|    }
   118|
   119|    // -- CBOR round-trip: structures --
   120|
   121|    @Test
   122|    fun cborArray() {
   123|        val arr = itemArrayOf(
   124|            Item.Str("x"),
   125|            Item.Num(42),
   126|            Item.Bool(true),
   127|            Item.Nil,
   128|        )
   129|        val decoded = Cbor.decode(Cbor.encode(arr)) as Item.Arr
   130|        assertEquals(4, decoded.size)
   131|        assertEquals("x", decoded[0].strValue)
   132|        assertEquals(42L, decoded[1].longValue)
   133|        assertEquals(true, decoded[2].boolValue)
   134|        assertTrue(decoded[3] is Item.Nil)
   135|    }
   136|
   137|    @Test
   138|    fun cborMap() {
   139|        val map = itemMapOf(
   140|            "name" to Item.Str("alice"),
   141|            "age" to Item.Num(30),
   142|            "active" to Item.Bool(true),
   143|        )
   144|        val decoded = Cbor.decode(Cbor.encode(map)) as Item.Map
   145|        assertEquals(3, decoded.size)
   146|        assertEquals("alice", decoded["name"]?.strValue)
   147|        assertEquals(30L, decoded["age"]?.longValue)
   148|        assertEquals(true, decoded["active"]?.boolValue)
   149|    }
   150|
   151|    @Test
   152|    fun cborNestedStructure() {
   153|        val doc = itemMapOf(
   154|            "user" to itemMapOf(
   155|                "name" to Item.Str("bob"),
   156|                "roles" to itemArrayOf(Item.Str("admin"), Item.Str("dev")),
   157|            ),
   158|            "count" to Item.Num(99),
   159|        )
   160|        val bytes = Cbor.encode(doc)
   161|        val decoded = Cbor.decode(bytes) as Item.Map
   162|
   163|        val user = decoded["user"] as Item.Map
   164|        assertEquals("bob", user["name"]?.strValue)
   165|        val roles = user["roles"] as Item.Arr
   166|        assertEquals(2, roles.size)
   167|        assertEquals("admin", roles[0].strValue)
   168|        assertEquals(99L, decoded["count"]?.longValue)
   169|    }
   170|
   171|    @Test
   172|    fun cborTag() {
   173|        val tagged = Item.Tag(1u, Item.Num(1363896240)) // epoch timestamp
   174|        val decoded = Cbor.decode(Cbor.encode(tagged)) as Item.Tag
   175|        assertEquals(1u, decoded.tag)
   176|        assertEquals(1363896240L, (decoded.item as Item.Num).value)
   177|    }
   178|
   179|    // -- CBOR round-trip: full document --
   180|
   181|    @Test
   182|    fun cborFullDocumentRoundTrip() {
   183|        val original = mapOf(
   184|            "_id" to "doc-001",
   185|            "type" to "user",
   186|            "name" to "alice",
   187|            "age" to 30,
   188|            "active" to true,
   189|            "tags" to listOf("dev", "kotlin"),
   190|            "address" to mapOf(
   191|                "city" to "NYC",
   192|                "zip" to "10001",
   193|            ),
   194|        )
   195|        val item = original.toItem()
   196|        val bytes = Cbor.encode(item)
   197|
   198|        // verify CBOR is compact
   199|        assertTrue(bytes.size < 120, "CBOR should be compact, got ${bytes.size} bytes")
   200|
   201|        val decoded = Cbor.decode(bytes)
   202|        val back = decoded.toAny() as Map<*, *>
   203|
   204|        assertEquals("doc-001", back["_id"])
   205|        assertEquals("alice", back["name"])
   206|        assertEquals(30L, back["age"])
   207|        assertEquals(true, back["active"])
   208|        assertEquals(listOf("dev", "kotlin"), back["tags"])
   209|        val addr = back["address"] as Map<*, *>
   210|        assertEquals("NYC", addr["city"])
   211|        assertEquals("10001", addr["zip"])
   212|    }
   213|
   214|    // -- CBOR size advantage --
   215|
   216|    @Test
   217|    fun cborMoreCompactThanJson() {
   218|        val doc = itemMapOf(
   219|            "name" to Item.Str("alice"),
   220|            "age" to Item.Num(30),
   221|            "active" to Item.Bool(true),
   222|        )
   223|        val cborBytes = Cbor.encode(doc)
   224|        val jsonText = """{"name":"alice","age":30,"active":true}"""
   225|        assertTrue(cborBytes.size < jsonText.length, "CBOR (${cborBytes.size}) should be shorter than JSON (${jsonText.length})")
   226|    }
   227|}
   228|