package borg.trikeshed.parse.confix

import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Confix serialization provider — round-trip proofs for JSON, CBOR, YAML.
 *
 * Each test encodes a @Serializable value through the Confix cursor pipeline:
 *
 *   value → JsonElement → ConfixDoc (faceted index)
 *         → wire bytes (JSON/CBOR/YAML via cursor walk)
 *
 *   wire bytes → ConfixDoc (Syntax scanner builds facets)
 *              → JsonElement (cursor walk over KeyToChild/DirectChildren)
 *              → value
 *
 * The cursor facet path ([ConfixIndexK.KeyToChild] for objects,
 * [ConfixIndexK.DirectChildren] for arrays) is exercised on every decode —
 * the format owns no separate DOM.
 */
class ConfixSerializationTest {

    @Serializable
    data class Board(
        val id: String,
        val columns: Int,
        val wipLimit: Int? = null,
        val tags: List<String> = emptyList(),
        val meta: Map<String, String> = emptyMap(),
        val active: Boolean = true,
    )

    private val sample = Board(
        id = "board-1",
        columns = 4,
        wipLimit = 3,
        tags = listOf("alpha", "beta"),
        meta = mapOf("owner" to "jim", "env" to "prod"),
        active = true,
    )

    @Test
    fun jsonRoundTripsThroughConfixCursors() {
        val json = ConfixString.Json.encode(sample)
        assertTrue(json.contains("\"id\":\"board-1\"") || json.contains("\"id\": \"board-1\""),
            "JSON must contain id field; got: $json")

        val back = ConfixString.Json.decode<Board>(json)
        assertEquals(sample, back)
    }

    @Test
    fun yamlRoundTripsThroughConfixCursors() {
        val yaml = ConfixString.Yaml.encode(sample)
        // YAML text is indentation-based
        assertTrue(yaml.contains("id: board-1") || yaml.contains("id:board-1") || yaml.contains("id:"),
            "YAML must contain id; got: $yaml")

        val back = ConfixString.Yaml.decode<Board>(yaml)
        assertEquals(sample, back)
    }

    @Test
    fun cborRoundTripsThroughConfixCursors() {
        val bytes = ConfixBinary.Default.encode(sample)
        assertTrue(bytes.isNotEmpty(), "CBOR bytes must be non-empty")

        val back = ConfixBinary.Default.decode<Board>(bytes)
        assertEquals(sample, back)
    }

    @Test
    fun jsonToYamlCrossFormatPreservesValue() {
        // Encode as JSON, decode as YAML-encoded text, prove semantic equality
        val json = ConfixString.Json.encode(sample)
        val fromJson = ConfixString.Json.decode<Board>(json)

        val yaml = ConfixString.Yaml.encode(fromJson)
        val fromYaml = ConfixString.Yaml.decode<Board>(yaml)

        assertEquals(sample, fromYaml, "JSON→value→YAML→value must preserve equality")
    }

    @Test
    fun emptyCollectionsRoundTrip() {
        val empty = Board(id = "x", columns = 0)
        val json = ConfixString.Json.encode(empty)
        val back = ConfixString.Json.decode<Board>(json)
        assertEquals(empty, back)
    }

    @Test
    fun nestedMapRoundTrips() {
        @Serializable data class Wrap(val inner: Map<String, Map<String, Int>>)
        val w = Wrap(mapOf("a" to mapOf("b" to 1, "c" to 2)))
        val json = ConfixString.Json.encode(w)
        val back = ConfixString.Json.decode<Wrap>(json)
        assertEquals(w, back)
    }

    @Test
    fun primitiveListRoundTrips() {
        val nums = listOf(1L, 2L, 3L)
        val json = ConfixString.Json.encode(nums)
        val back = ConfixString.Json.decode<List<Long>>(json)
        assertEquals(nums, back)
    }

    @Test
    fun scalarStringRoundTrips() {
        val s = "hello world"
        val json = ConfixString.Json.encode(s)
        val back = ConfixString.Json.decode<String>(json)
        assertEquals(s, back)
    }
}
