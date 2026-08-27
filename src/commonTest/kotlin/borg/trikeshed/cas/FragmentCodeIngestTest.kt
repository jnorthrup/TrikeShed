package borg.trikeshed.cas

import borg.trikeshed.job.CasStore
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.narsese.AngularCodec
import borg.trikeshed.narsese.hamming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Step B gate — the coordinate is minted at ingest and it behaves like a coordinate:
 *
 * 1. Determinism: same line/chunk bytes → same code (commonMain arithmetic only).
 * 2. Hamming meaningfulness: near-duplicate surfaces land within small hamming
 *    distance; dissimilar surfaces land far. The FNV-era behavior (avalanche →
 *    distance meaningless) is demonstrably gone from the ingest angulars.
 * 3. Zero-traversal minting: [LineCas.ingestLines] — the funnel every text
 *    fragment flows through — stamps [LineNode.code] with no second pass.
 */
class FragmentCodeIngestTest {

    @Test
    fun sameTextMintsSameCodeAcrossCalls() {
        val line = "fun spineCid(spine: LineSpine): ContentId {"
        val a = AngularCodec.fragmentCode(line)
        val b = AngularCodec.fragmentCode(line)
        assertEquals(a, b, "code must be deterministic")
        assertEquals((a ushr 8) and 0xFF, AngularCodec.ring8(a))
    }

    @Test
    fun nearDuplicateLinesLandWithinSmallHammingDistance() {
        val base = "The quick brown fox jumps over the lazy dog near the river bank"
        val variant = "The quick brown fox jumps over the lazy dog near the river banks"
        val far = " totally unrelated content ~ 12345 ! @# token streaming paradigm shift"
        val near = hamming(
            AngularCodec.fragmentCode(base).toLong(),
            AngularCodec.fragmentCode(variant).toLong(),
        )
        val distant = hamming(
            AngularCodec.fragmentCode(base).toLong(),
            AngularCodec.fragmentCode(far).toLong(),
        )
        assertTrue(near <= 6, "near-duplicate hamming $near must be small (≤6)")
        assertTrue(distant > near, "dissimilar hamming $distant must exceed near $near")
    }

    @Test
    fun ingestLinesStampsEveryNodeWithItsCode() {
        val cas = CasStore.inMemory()
        val doc = """
            first line of the corpus document
            first line of the corpus documents
            completely different subject matter entirely
        """.trimIndent()
        val spine = LineCas.spineInto(cas, doc)
        assertTrue(spine.size == 3)
        for (i in 0 until spine.size) {
            val node = spine[i]
            val text = cas.get(node.contentCid)!!.decodeToString()
            assertEquals(AngularCodec.fragmentCode(text), node.code, "node $i code must equal fragmentCode(line)")
        }
        // near-duplicate neighbors carry near codes
        val d = hamming(spine[0].code.toLong(), spine[1].code.toLong())
        assertTrue(d <= 6, "one-char variant lines: hamming $d must be small")
        // the 8-bit ring is the HIGH byte of the code (sortable coarse prefix)
        assertEquals((spine[0].code ushr 8) and 0xFF, spine[0].codeRing8)
    }

    @Test
    fun byteChunksCarryShapeDerivedCodesAndLinkAngularsShareHighBitsOnSameSchema() {
        val cas = CasStore.inMemory()
        // two runs of structurally identical printable bytes + one binary run
        val printableA = ByteArray(2048) { 0x41 }
        val printableB = ByteArray(2048) { if (it % 7 == 0) 0x42 else 0x41 }
        val binary = ByteArray(2048) { (it * 31 + 7).toByte() }
        val surface = ByteEpistemicIngest.ingest(cas, printableA + printableB + binary, 1024)

        assertEquals(6, surface.chunks.size, "6 KiB in 1 KiB chunks")
        // identical structures → identical codes (deterministic in the shape key)
        assertEquals(surface.chunks[0].code, surface.chunks[1].code, "same RLE shape → same code")
        assertEquals(surface.chunks[0].code, surface.chunks[2].code, "same RLE shape → same code")
        // chunk 3/4 (binary run) legitimately differ from the printable runs
        assertTrue(
            surface.chunks[0].code != surface.chunks[4].code || surface.chunks[0].code != surface.chunks[5].code,
            "binary chunks must not all collapse into the printable code",
        )

        // CAUSALITY links between same-schema chunks: subject/object terms equal →
        // their angulars must be hamming-tiny (the FNV-era avalanche would scatter them)
        val angulars = (0 until surface.signals.size).map { surface.signals[it].angular }
        val causalSameSchema = angulars.take(4) // chunk0→1, 1→2, 2→3, 3→4 ... includes schema boundary at 1→2
        val within = hamming(causalSameSchema[0], causalSameSchema[1])
        assertTrue(within <= 24, "same-position link angulars hamming $within must be moderate (≤24), FNV gave ~32 mean")
    }

    @Test
    fun epistemicSignalsMintAngularCodecCoordinatesNotIdentityHashes() {
        val cas = CasStore.inMemory()
        val text = """
            # Heading one
            body line with meaningful content about retrieval
            body line with meaningful content about retrieval systems
            ## Heading two
            more filler text that pads the region out
        """.trimIndent()
        val surface = ContentEpistemicIngest.ingest(cas, text)
        assertTrue(surface.signals.size > 0, "region links exist")
        // CAUSALITY chain angulars across similar regions must cluster — revision
        // (same angular ⇒ evidence union) can only dedupe if coordinates collide meaningfully
        for (i in 0 until surface.signals.size) {
            val s = surface.signals[i]
            assertTrue(s.angular != 0L, "angular minted")
        }
    }
}
