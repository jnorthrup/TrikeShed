package borg.trikeshed.loom

import borg.trikeshed.ipns.*
import borg.trikeshed.lib.*
import kotlin.test.*

/**
 * storage.rs, config.rs and ledger.rs against LoomStoreVectors: the files the Rust Store wrote decode
 * and re-encode to the same bytes, verify under their configuration, and every configuration meets
 * the released crate's verdict.
 */
class LoomStoreTest {
    val crypto: IpnsCrypto = Ed25519

    fun config(json: String): Config = config_json(json.replace("@STORE@", "/var/loom/store").replace("@KEY@", "/var/loom/key").encodeToByteArray())

    fun producers(config: Config): Map<String, ByteArray> =
        config.members.view.toList().filter { it.has(Role.Producer) }.associate { it.id to it.key() }

    @Test
    fun configurationsMeetTheReleasedVerdicts() {
        for ((json, verdict) in LoomStoreVectors.CONFIGS.view) {
            val judged = try {
                config_json(json.encodeToByteArray()).validate()
                "ok"
            } catch (refused: IllegalStateException) {
                refused.message
            }
            assertEquals(verdict, judged, json)
        }
    }

    @Test
    fun storedRecordsAndLedgersReencodeAndVerify() {
        for ((json, files) in s_[LoomStoreVectors.CONFIG_A j LoomStoreVectors.STORE_A, LoomStoreVectors.CONFIG_B j LoomStoreVectors.STORE_B].view) {
            val config = config(json).also { it.validate() }
            val archive = config.archive?.let { config.member(it) } ?: config.legacy_archive_read_trust
            var records = 0
            for ((name, hex) in files.view) {
                val bytes = hex.hexToByteArray()
                when {
                    name.startsWith(".ledger-") -> {
                        val (stream, slots) = LedgerSlots.decode(bytes, config, crypto)
                        assertEquals(name.removePrefix(".ledger-").removeSuffix(".cbor"), stream)
                        assertContentEquals(bytes, slots.encode(stream))
                    }
                    name.length == 69 -> {
                        val entry = Entry.decode(bytes)
                        assertEquals("${entry.id.toHexString()}.cbor", name)
                        assertContentEquals(bytes, entry.encode())
                        entry.verify(producers(config), archive, config, crypto)
                        records++
                    }
                }
            }
            assertTrue(records >= 2)
        }
    }

    @Test
    fun ledgerSlotsRefuseANonCanonicalFile() {
        val config = config(LoomStoreVectors.CONFIG_A)
        val orders = LoomStoreVectors.STORE_A.view.first { it.a == ".ledger-orders.cbor" }.b.hexToByteArray()
        val (_, slots) = LedgerSlots.decode(orders, config, crypto)
        assertEquals(2uL, slots.top())
        assertEquals(1uL, slots.settled_version())
        val tampered = orders.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertEquals("ledger checksum", assertFailsWith<IllegalStateException> { LedgerSlots.decode(tampered, config, crypto) }.message)
        val head = LedgerHead.of("orders", slots, 1uL, false)
        assertContentEquals(head.to_bytes(), LedgerHead.from_bytes(head.to_bytes()).to_bytes())
    }
}
