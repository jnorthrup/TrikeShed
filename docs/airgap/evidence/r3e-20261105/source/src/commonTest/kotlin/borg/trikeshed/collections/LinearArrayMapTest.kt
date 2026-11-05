package borg.trikeshed.collections

import borg.trikeshed.lib.*
import borg.trikeshed.parse.*
import kotlin.test.*

class LinearArrayMapTest {
    @Test fun reifiedRowsRetainMapAndEntryHashEqualityAcrossFieldOrder(): Unit {
        val row = _m["source" j _m["type" j "uri", "value" j "urn:source"],
            "term" j _m["type" j "literal", "value" j "Tenant"], "optional" j null]
        val parsed = reifyMap("""{"optional":null,"term":{"value":"Tenant","type":"literal"},"source":{"value":"urn:source","type":"uri"}}""")
        assertEquals(row, parsed); assertEquals(parsed, row)
        repeat(3) { assertEquals(row.hashCode(), parsed.hashCode()) }
        val index = HashMap<Map<*, *>, Int>(); index[row] = 7
        assertEquals(7, index[parsed])
        val reverse = HashMap<Map<*, *>, Int>(); reverse[parsed] = 9
        assertEquals(9, reverse[row])
        assertEquals(row.entries, parsed.entries); assertEquals(parsed.entries, row.entries)
        assertEquals(_s[row], _s[parsed]); assertEquals(_s[parsed], _s[row])
    }

    @Test fun indexedReifiedMapsKeepHashesAndEntryMembershipWithNulls(): Unit {
        val row = (0 until 12).associate { ("field-$it" j if (it % 2 == 0) null else "value-$it").pair }
        val parsed = reifyMap(jsonOf(row))
        assertEquals(row.hashCode(), parsed.hashCode())
        assertEquals("value-11", parsed["field-11"])
        assertTrue(parsed.containsKey("field-10")); assertNull(parsed["field-10"])
        assertEquals(row.hashCode(), parsed.hashCode())
        assertEquals(row.entries, parsed.entries); assertEquals(parsed.entries, row.entries)
        assertEquals(row.entries.hashCode(), parsed.entries.hashCode())
    }
}
