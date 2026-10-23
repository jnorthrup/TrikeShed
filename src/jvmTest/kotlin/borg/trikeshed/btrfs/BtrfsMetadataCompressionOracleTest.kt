package borg.trikeshed.btrfs

import borg.trikeshed.job.sha256
import borg.trikeshed.tilting.lz4.Lz4Block
import borg.trikeshed.tilting.lz4.Lz4Ffm
import borg.trikeshed.userspace.btrfs.BtrfsCodecLE
import com.github.luben.zstd.Zstd
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.*

/** Common codecs interoperate with independent native implementations in both directions. */
class BtrfsMetadataCompressionOracleTest {
    @Test fun zstdFramesInteroperateWithLibzstd() {
        for (size in arrayOf(4096, 16384, 300_000)) {
            val plain = metadata(size)
            val ours = BtrfsMetadataCompression.ZSTD.encode(plain)
            assertEquals(BtrfsMetadataCompression.ZSTD, BtrfsMetadataCompression.storedCompression(ours))
            val payload = ours.copyOfRange(32, ours.size - 32)
            assertContentEquals(plain, Zstd.decompress(payload, plain.size))
            val theirs = envelope(BtrfsMetadataCompression.ZSTD, plain.size, Zstd.compress(plain, 3))
            assertContentEquals(plain, BtrfsMetadataCompression.decode(theirs))
        }
    }

    @Test fun lz4BlocksInteroperateWithLiblz4WhenAvailable() {
        val native = Lz4Ffm.loadOrNull()
        assumeTrue(native != null, "liblz4 is absent; common tests retain independent liblz4 fixtures")
        val codec = requireNotNull(native)
        for (size in arrayOf(4096, 16384, 300_000)) {
            val plain = metadata(size)
            val ours = BtrfsMetadataCompression.LZ4.encode(plain)
            assertEquals(BtrfsMetadataCompression.LZ4, BtrfsMetadataCompression.storedCompression(ours))
            val output = ByteArray(plain.size)
            assertEquals(plain.size, codec.decompress(ours, 32, ours.size - 64, output, 0, output.size))
            assertContentEquals(plain, output)
            val buffer = ByteArray(Lz4Block.bound(plain.size))
            val length = codec.compress(plain, 0, plain.size, buffer, 0)
            val theirs = envelope(BtrfsMetadataCompression.LZ4, plain.size, buffer.copyOf(length))
            assertContentEquals(plain, BtrfsMetadataCompression.decode(theirs))
        }
    }

    private fun metadata(size: Int): ByteArray {
        val row = "generation=11; layout=SPAN; members=alpha.img,beta.img; retained=previous;"
        return ByteArray(size) { row[it % row.length].code.toByte() }
    }

    private fun envelope(codec: BtrfsMetadataCompression, decodedSize: Int, payload: ByteArray): ByteArray {
        val bytes = ByteArray(payload.size + 64)
        "TSMETA01".encodeToByteArray().copyInto(bytes)
        BtrfsCodecLE.writeInt(1, bytes, 8)
        BtrfsCodecLE.writeInt(codec.code, bytes, 12)
        BtrfsCodecLE.writeInt(decodedSize, bytes, 16)
        BtrfsCodecLE.writeInt(payload.size, bytes, 20)
        payload.copyInto(bytes, 32)
        val end = bytes.size - 32
        sha256(bytes.copyOfRange(0, end)).copyInto(bytes, end)
        return bytes
    }
}
