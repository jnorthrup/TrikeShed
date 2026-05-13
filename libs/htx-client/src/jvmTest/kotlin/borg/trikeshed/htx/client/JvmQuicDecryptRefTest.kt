package borg.trikeshed.htx.client

import borg.trikeshed.tls.codec.aead.DefaultAes128Gcm
import borg.trikeshed.tls.codec.hash.DefaultSha256
import borg.trikeshed.tls.codec.kdf.DefaultHkdfSha256
import borg.trikeshed.tls.codec.CommonTlsClientHandshake
import borg.trikeshed.tls.codec.CommonTlsRecordCodec
import borg.trikeshed.tls.codec.ecdh.DefaultX25519
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Decrypt the working aioquic reference packet with TrikeShed crypto.
 * If decryption succeeds, our key derivation matches — the bug is in our ClientHello.
 */
class JvmQuicDecryptRefTest {

    private fun hex(buf: ByteArray, len: Int = buf.size): String =
        buf.copyOfRange(0, len.coerceAtMost(buf.size)).joinToString(" ") { "%02x".format(it) }

    private fun aesEcbEncrypt(key: ByteArray, block: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(block)
    }

    @Test
    fun `decrypt aioquic reference packet with TrikeShed crypto`() {
        // Load the captured aioquic packet
        val packet = File("/tmp/aioquic_initial.bin").readBytes()
        println("[DECRYPT] loaded ${packet.size} bytes from aioquic reference")

        // Parse header
        val firstByte = packet[0].toInt() and 0xFF
        println("[DECRYPT] first_byte=0x${"%02x".format(firstByte)} = ${Integer.toBinaryString(firstByte).padStart(8, '0')}")
        assertTrue(firstByte and 0x80 != 0, "Must be long header")

        val version = ((packet[1].toInt() and 0xFF) shl 24) or
                ((packet[2].toInt() and 0xFF) shl 16) or
                ((packet[3].toInt() and 0xFF) shl 8) or
                (packet[4].toInt() and 0xFF)
        assertEquals(0x00000001, version, "Must be QUIC v1")

        val dcidLen = packet[5].toInt() and 0xFF
        val dcid = packet.copyOfRange(6, 6 + dcidLen)
        println("[DECRYPT] dcid=${hex(dcid)}")

        var pos = 6 + dcidLen
        val scidLen = packet[pos].toInt() and 0xFF
        val scid = packet.copyOfRange(pos + 1, pos + 1 + scidLen)
        pos += 1 + scidLen
        println("[DECRYPT] scid=${hex(scid)}")

        // Token length (varint)
        val tokenLen = packet[pos].toInt() and 0xFF
        pos += 1 // assume 1-byte varint (token_len < 0x40)
        pos += tokenLen

        // Length (varint)
        val lengthFirst = packet[pos].toInt() and 0xFF
        val length: Int
        val lengthVarintLen: Int
        if (lengthFirst < 0x40) {
            length = lengthFirst; lengthVarintLen = 1
        } else if (lengthFirst < 0x80) {
            length = ((lengthFirst and 0x3F) shl 8) or (packet[pos + 1].toInt() and 0xFF)
            lengthVarintLen = 2
        } else {
            length = (((packet[pos].toInt() and 0xFF) and 0x3F) shl 24) or
                    ((packet[pos + 1].toInt() and 0xFF) shl 16) or
                    ((packet[pos + 2].toInt() and 0xFF) shl 8) or
                    (packet[pos + 3].toInt() and 0xFF)
            lengthVarintLen = 4
        }
        pos += lengthVarintLen
        println("[DECRYPT] length=$length (${lengthVarintLen} varint bytes)")

        val pnOffset = pos
        // We don't know pn_len yet — need to remove header protection first

        // Derive Initial keys from DCID (same as what the client did)
        val sha256 = DefaultSha256()
        val hkdf = DefaultHkdfSha256(sha256)
        val aes = DefaultAes128Gcm()

        val initialSalt = byteArrayOf(
            0x38, 0x76, 0x2C.toByte(), 0xF7.toByte(), 0xF5.toByte(), 0x59, 0x34, 0xB3.toByte(),
            0x4D, 0x17, 0x9A.toByte(), 0xE6.toByte(), 0xA4.toByte(), 0xC8.toByte(), 0x0C, 0xAD.toByte(),
            0xCC.toByte(), 0xBB.toByte(), 0x7F, 0x0A
        )
        val initialSecret = hkdf.extract(initialSalt, dcid)
        val clientSecret = hkdf.expandLabel(initialSecret, "client in", ByteArray(0), 32)
        val clientKey = hkdf.expandLabel(clientSecret, "quic key", ByteArray(0), 16)
        val clientIv = hkdf.expandLabel(clientSecret, "quic iv", ByteArray(0), 12)
        val clientHp = hkdf.expandLabel(clientSecret, "quic hp", ByteArray(0), 16)

        println("[DECRYPT] client_key=${hex(clientKey)}")
        println("[DECRYPT] client_iv=${hex(clientIv)}")
        println("[DECRYPT] client_hp=${hex(clientHp)}")

        // Header protection: sample 16 bytes starting at pnOffset
        val sample = packet.copyOfRange(pnOffset, pnOffset + 16)
        println("[DECRYPT] sample=${hex(sample)}")

        val mask = aesEcbEncrypt(clientHp, sample)
        println("[DECRYPT] mask=${hex(mask)}")

        // Remove header protection from first byte
        val unprotectedFirstByte = (firstByte xor (mask[0].toInt() and 0x0F))
        val pnLen = (unprotectedFirstByte and 0x03) + 1
        println("[DECRYPT] unprotected_first=0x${"%02x".format(unprotectedFirstByte)} pn_len=$pnLen")

        // Reconstruct unprotected header
        val unprotectedHeader = packet.copyOfRange(0, pnOffset + pnLen)
        unprotectedHeader[0] = unprotectedFirstByte.toByte()
        // Remove PN protection
        for (i in 0 until pnLen) {
            unprotectedHeader[pnOffset + i] = (packet[pnOffset + i].toInt() xor mask[1 + i].toInt()).toByte()
        }

        // Read packet number
        val pn = unprotectedHeader.copyOfRange(pnOffset, pnOffset + pnLen)
        println("[DECRYPT] packet_number=${hex(pn)}")

        // Construct nonce: IV XOR PN (left-padded to 12 bytes)
        val nonce = ByteArray(12)
        for (i in clientIv.indices) nonce[i] = clientIv[i]
        for (i in pn.indices) nonce[12 - pn.size + i] = (nonce[12 - pn.size + i].toInt() xor pn[i].toInt()).toByte()
        println("[DECRYPT] nonce=${hex(nonce)}")

        // AEAD decrypt: ciphertext starts after PN, length = length - pnLen
        val ctStart = pnOffset + pnLen
        val ctLen = length - pnLen
        val ciphertextWithTag = packet.copyOfRange(ctStart, ctStart + ctLen)
        println("[DECRYPT] ciphertext+tag ${ciphertextWithTag.size} bytes at offset $ctStart")

        // Decrypt
        val plaintext = aes.open(clientKey, nonce, unprotectedHeader, ciphertextWithTag)
        if (plaintext == null) {
            println("[DECRYPT] FAILED — AEAD decryption returned null!")
            println("[DECRYPT] This means our key derivation or AEAD does NOT match aioquic's")
            assertTrue(false, "AEAD decryption of aioquic packet failed — crypto mismatch")
        } else {
            println("[DECRYPT] SUCCESS — decrypted ${plaintext.size} bytes")
            println("[DECRYPT] first 60: ${hex(plaintext, 60)}")

            // Parse CRYPTO frame
            var p = 0
            val frameType = plaintext[p++].toInt() and 0xFF
            println("[DECRYPT] frame_type=0x${"%02x".format(frameType)} (6=CRYPTO)")
            assertEquals(0x06, frameType, "First frame must be CRYPTO")

            // Offset (varint)
            val offsetFirst = plaintext[p++].toInt() and 0xFF
            val offset = if (offsetFirst < 0x40) offsetFirst else 0 // assume small
            println("[DECRYPT] crypto_offset=$offset")

            // Length (varint)
            val clFirst = plaintext[p++].toInt() and 0xFF
            val cryptoLen: Int
            if (clFirst < 0x40) {
                cryptoLen = clFirst
            } else if (clFirst < 0x80) {
                cryptoLen = ((clFirst and 0x3F) shl 8) or (plaintext[p++].toInt() and 0xFF)
            } else {
                cryptoLen = (((clFirst and 0x3F) shl 24) or
                        ((plaintext[p++].toInt() and 0xFF) shl 16) or
                        ((plaintext[p++].toInt() and 0xFF) shl 8) or
                        (plaintext[p++].toInt() and 0xFF))
            }
            println("[DECRYPT] crypto_length=$cryptoLen")

            val clientHello = plaintext.copyOfRange(p, p + cryptoLen)
            println("[DECRYPT] ClientHello ${clientHello.size} bytes")
            println("[DECRYPT] CH first 80: ${hex(clientHello, 80)}")

            // Parse TLS ClientHello
            val chType = clientHello[0].toInt() and 0xFF
            val chLen = ((clientHello[1].toInt() and 0xFF) shl 16) or
                    ((clientHello[2].toInt() and 0xFF) shl 8) or
                    (clientHello[3].toInt() and 0xFF)
            println("[DECRYPT] CH type=0x${"%02x".format(chType)} len=$chLen")
            assertEquals(0x01, chType, "Must be ClientHello")

            // Compare with our ClientHello
            val ourHello = buildOurClientHello("google.com")
            println()
            println("[OURS] ClientHello ${ourHello.size} bytes")
            println("[OURS] CH first 80: ${hex(ourHello, 80)}")
            println()

            // Find differences
            val minLen = minOf(clientHello.size, ourHello.size)
            var firstDiff = -1
            for (i in 0 until minLen) {
                if (clientHello[i] != ourHello[i]) {
                    firstDiff = i
                    println("[DIFF] first difference at byte $i: aioquic=0x${"%02x".format(clientHello[i])} ours=0x${"%02x".format(ourHello[i])}")
                    println("[DIFF]   aioquic context: ${hex(clientHello, minOf(i + 10, clientHello.size))}")
                    println("[DIFF]   ours context:    ${hex(ourHello, minOf(i + 10, ourHello.size))}")
                    break
                }
            }
            if (firstDiff == -1) {
                if (clientHello.size != ourHello.size) {
                    println("[DIFF] ClientHellos match for ${minLen} bytes but differ in length: aioquic=${clientHello.size} ours=${ourHello.size}")
                } else {
                    println("[MATCH] ClientHellos are byte-identical!")
                }
            }

            // Dump both full for comparison
            println()
            println("=== AIOQUIC ClientHello (${clientHello.size} bytes) ===")
            for (i in 0 until clientHello.size step 32) {
                println("${"%04x".format(i)}: ${hex(clientHello.copyOfRange(i, minOf(i + 32, clientHello.size)))}")
            }
            println()
            println("=== OUR ClientHello (${ourHello.size} bytes) ===")
            for (i in 0 until ourHello.size step 32) {
                println("${"%04x".format(i)}: ${hex(ourHello.copyOfRange(i, minOf(i + 32, ourHello.size)))}")
            }
        }
    }

    private fun buildOurClientHello(serverName: String): ByteArray {
        val sha256 = DefaultSha256()
        val hkdf = DefaultHkdfSha256(sha256)
        val aes = DefaultAes128Gcm()
        val x25519 = DefaultX25519()
        val codec = CommonTlsRecordCodec(aes)
        val hs = CommonTlsClientHandshake(sha256, x25519, hkdf, codec, serverName, listOf("h3"))
        return hs.buildClientHello()
    }
}
