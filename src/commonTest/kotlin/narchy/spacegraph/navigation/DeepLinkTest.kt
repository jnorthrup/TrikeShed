package narchy.spacegraph.navigation

import borg.trikeshed.lib.*
import narchy.spacegraph.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Expected values are upstream packages/navigation/src/deep-link.test.ts, copied by hand.


class DeepLinkTest {
    private val options = DeepLinkOptions(prefix = "#spacegraph:")

    // describe('encodeDeepLink')
    @Test fun encodesLayoutAndLevel() {
        assertEquals("#spacegraph:lanes:execution", encodeDeepLink(DeepLinkParts("lanes", "execution"), options))
    }

    @Test fun encodesLayoutLevelAndNode() {
        assertEquals(
            "#spacegraph:lanes:execution:node-123",
            encodeDeepLink(DeepLinkParts("lanes", "execution", "node-123"), options),
        )
    }

    @Test fun handlesDifferentLayoutsAndLevels() {
        assertEquals("#spacegraph:tree:overview", encodeDeepLink(DeepLinkParts("tree", "overview"), options))
        assertEquals(
            "#spacegraph:timeline:detail:abc",
            encodeDeepLink(DeepLinkParts("timeline", "detail", "abc"), options),
        )
    }

    // describe('decodeDeepLink')
    @Test fun decodesLayoutAndLevel() {
        assertEquals(
            DeepLinkParts("lanes", "execution", null),
            decodeDeepLink("#spacegraph:lanes:execution", options),
        )
    }

    @Test fun decodesLayoutLevelAndNode() {
        assertEquals(
            DeepLinkParts("lanes", "execution", "node-123"),
            decodeDeepLink("#spacegraph:lanes:execution:node-123", options),
        )
    }

    @Test fun returnsNullForWrongPrefix() {
        assertNull(decodeDeepLink("#graph:lanes:execution", options))
        assertNull(decodeDeepLink("spacegraph:lanes:execution", options))
        assertNull(decodeDeepLink("", options))
    }

    @Test fun returnsNullForIncompleteSegments() {
        assertNull(decodeDeepLink("#spacegraph:lanes", options))
        assertNull(decodeDeepLink("#spacegraph:", options))
    }

    // describe('isDeepLink')
    @Test fun isTrueForMatchingPrefix() {
        assertTrue(isDeepLink("#spacegraph:lanes:execution", options))
        assertTrue(isDeepLink("#spacegraph:tree:overview:node", options))
    }

    @Test fun isFalseForNonMatchingPrefix() {
        assertFalse(isDeepLink("#graph:lanes:execution", options))
        assertFalse(isDeepLink("spacegraph:lanes:execution", options))
        assertFalse(isDeepLink("", options))
    }

    // describe('createDeepLinkCodec')
    @Test fun createsCodecWithFixedPrefix() {
        val codec = createDeepLinkCodec("#spacegraph:")
        assertEquals("#spacegraph:lanes:execution", codec.encode(DeepLinkParts("lanes", "execution")))
        assertEquals(DeepLinkParts("lanes", "execution", "node"), codec.decode("#spacegraph:lanes:execution:node"))
        assertTrue(codec.isDeepLink("#spacegraph:lanes:execution"))
        assertFalse(codec.isDeepLink("#other:lanes:execution"))
    }

    // describe('round-trip')
    @Test fun roundTripsWithNode() {
        val parts = DeepLinkParts("lanes", "execution", "node-123")
        assertEquals(parts, decodeDeepLink(encodeDeepLink(parts, options), options))
    }

    @Test fun roundTripsWithoutNode() {
        val parts = DeepLinkParts("tree", "overview")
        assertEquals(parts, decodeDeepLink(encodeDeepLink(parts, options), options))
    }
}
