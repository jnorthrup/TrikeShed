package borg.trikeshed.tilting.zran

import borg.trikeshed.isam.*
import borg.trikeshed.job.*
import kotlin.random.Random
import kotlin.test.*

class ZstdDictionaryTest {
    @Test fun trainedHuffmanTableIsRestoredBeforeEveryFrame() {
        // Independent zstd-jni 1.5.7-23 fixture: 128 x 1024 samples drawn from a..h with
        // java.util.Random(7), 512-byte training output, then the next 64 random bytes.
        val dictionary = hex("37a430ecf75ed1560d10f8ca66ff5f6b4a49a6349f7b0300303080e0a0d152b21b000004a02f9211000000000000000000000000000000000000000000000000c4448765850444836291401a0d26391c45490aa9a201000000010000000400000008000000666865616362636263656162646161666863666764646463686464656465686861636663656161656364626163616564656862676668666663626265686467686368686465616865636364686363676268626467656168616665616667656267626268646767656762636566666467676866646662646362666366646165666661636762626163626166636566666161646366626362636366636164626461656161626368686762666768626166676661646362646368676863646262646464616262676563616862656565626262686866616468686868686668666361676365686865666563646465656567656365646163676463626861636165676367666662686865666766636367626565626164626266666567616566666466656868666363666664616368626265616468666761656163646765666263676768646568666465656368656265636265646567626164656165636664666368686761686463656867626465616265666364686566636265616162626463626266676268666367616763656365666361626761646862646861656668626862")
        val frame = hex("28b52ffd27f75ed156404d0100c30308fdf8e0473fbaf4e3e3eec7473e3e3ecb8fedfbe383f5c7d2ab3abf7cccf287c901008fc068018df32274")
        val text = "ddgegfegabgceachdhbfbhfacegfdeeghgggahgbgcheeaeefgheefacfefedeef".encodeToByteArray()
        assertEquals(3, frame[13].toInt() and 3, "Treeless literals must use the trained Huffman table")
        val state = ZstdDictionary(dictionary)
        val decoder = ZstdDecoder(state)
        repeat(3) {
            assertContentEquals(text, decoder.decompress(frame))
            val other = ByteArray(4096) { (it % 13).toByte() }
            assertContentEquals(other, decoder.decompress(ZstdEncoder(dictionary = state).compress(other)))
        }
    }

    @Test fun trainedReferenceFrameDecodesWithOwnedStateAndDestinationBounds() {
        val bytes = trained()
        val state = ZstdDictionary(bytes)
        bytes.fill(0)
        assertEquals(0x4f779156L, state.id)
        val decoder = ZstdDecoder(state)
        val source = byteArrayOf(9, 8, 7) + referenceFrame
        repeat(3) {
            val destination = ByteArray(referenceText.size + 14) { 99 }
            assertEquals(source.size, decoder.decodeInto(source, 3, source.size, destination, 7, referenceText.size))
            assertContentEquals(referenceText, destination.copyOfRange(7, destination.size - 7))
            assertTrue(destination.take(7).all { it == 99.toByte() })
            assertTrue(destination.takeLast(7).all { it == 99.toByte() })
        }
        assertContentEquals(referenceText + referenceText, decoder.decompress(referenceFrame + referenceFrame))
    }

    @Test fun referenceFrameRequiresItsDictionaryAndRejectsTruncationAndCorruption() {
        assertFails { ZstdDecoder().decompress(referenceFrame) }
        val wrong = trained().also { it[4] = (it[4].toInt() xor 1).toByte() }
        assertFails { ZstdDecoder(ZstdDictionary(wrong)).decompress(referenceFrame) }
        val decoder = DictionaryLeafCodec(trained()).decoder()
        for (size in 0 until referenceFrame.size) {
            assertFails { decoder(referenceFrame.copyOf(size), ByteArray(referenceText.size)) }
        }
        val corrupt = referenceFrame.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertFails { decoder(corrupt, ByteArray(referenceText.size)) }
        assertFails { decoder(referenceFrame + byteArrayOf(0), ByteArray(referenceText.size)) }
        assertFails { decoder(referenceFrame, ByteArray(referenceText.size - 1)) }
        assertFails { decoder(referenceFrame, ByteArray(referenceText.size + 1)) }
    }

    @Test fun rawDictionaryMatchesHistoryBeyondTheFrameWindow() {
        val bytes = Random(17).nextBytes(8192)
        val input = bytes.copyOfRange(512, 1536)
        val codec = DictionaryLeafCodec(bytes)
        val frame = codec.encoder()(input, input.size)
        assertTrue(frame.size < 64, "The dictionary must supply actual matches, not just a CID")
        assertFails { ZstdDecoder().decompress(frame) }
        val result = ByteArray(input.size)
        codec.decoder()(frame, result)
        assertContentEquals(input, result)
    }

    @Test fun rawAndTrainedFramesReuseScratchAcrossBlockAndContentBoundaries() {
        val random = Random(29)
        for (bytes in listOf(referenceText, trained())) {
            val codec = DictionaryLeafCodec(bytes)
            val encode = codec.encoder()
            val decode = codec.decoder()
            for (length in listOf(0, 1, 31, 32, 255, 256, 65791, 65792, ZSTD_BLOCK_MAX,
                ZSTD_BLOCK_MAX + 1, ZSTD_BLOCK_MAX * 2 + 19)) {
                for (input in listOf(ByteArray(length) { referenceText[it % referenceText.size] }, random.nextBytes(length))) {
                    val frame = encode(input, input.size)
                    val result = ByteArray(input.size)
                    decode(frame, result)
                    assertContentEquals(input, result, "size=$length")
                }
            }
        }
    }

    @Test fun malformedDictionaryAndUnavailableCasIdentityFailClosed() {
        assertFails { DictionaryLeafCodec(byteArrayOf()) }
        for (size in 8 until 128) assertFails { ZstdDictionary(trained().copyOf(size)) }
        val zeroId = trained().also { it.fill(0, 4, 8) }
        assertFails { ZstdDictionary(zeroId) }
        val cas = CasStore.inMemory()
        val cid = cas.put(trained())
        val codec = CasLeafCodec(cas, dictionary = cid)
        codec.prepareWrite()
        val resolved = codec.resolve(cid)
        assertEquals(cid, resolved.dictionary)
        val decoded = ByteArray(referenceText.size)
        resolved.decoder()(referenceFrame, decoded)
        assertContentEquals(referenceText, decoded)
        assertFails { CasLeafCodec(CasStore.inMemory()).resolve(cid) }
    }

    companion object {
        // zstd-jni 1.5.7-23: trainFromBuffer(128 synthetic review samples, 512-byte dictionary),
        // then ZstdCompressCtx.setChecksum(true).loadDict(dictionary).compress(sample[17]).
        val referenceText = "synthetic medical/legal review: evidence retained; human admission required; case=17; party=3".encodeToByteArray()
        val referenceFrame = hex("28b52ffd275691774f5dc50100c40273796e746865746963206d65646963616c2f6c6567616c207265766965773a2065766964656e63653d313d3302cc883b1000b0dd569d31369e78")
        fun trained(): ByteArray = hex(
            "37a430ec5691774f1610103ca403cef33ccff33cefccca24534a29c5cfac02330400000052005b0700003460c08081013126040b03030303030303030303030303030303030303038291c160301810c2c798999999d9b601f4f333360cc330c618638c31c618638c31333333b36d03010000000400000008000000" +
            "2072657461696e65643b2068756d616e2061646d697373696f6e2072657175697265643b206361736572657461696e65643b2068756d616e2061646d697373696f6e2072657175697265643b20636173653d38373b20706172747975697265643b20636173653d37363b2070617274797461696e65643b2068756d616e2061646d697373696f6e2072657175697265643b20636173653d36393b2070617274793d367461696e65643b2068756d616e2061646d697373696f6e2072657175697265643b20636173653d36353b2070617274793d327461696e65643b2068756d616e2061646d697373696f6e2072657175697265643b20636173653d35343b2070617274793d357461696e65643b2068756d616e2061646d697373696f6e2072657175697265643b20636173653d34333b2070617274793d317461696e65643b2068756d616e2061646d697373696f6e2072657175697265643b20636173653d33323b2070617274793d347461696e65643b2068756d616e2061646d697373696f6e20726571")
        fun hex(value: String): ByteArray = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
