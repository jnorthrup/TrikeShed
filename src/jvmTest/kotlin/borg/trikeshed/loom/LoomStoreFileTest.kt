package borg.trikeshed.loom

import borg.trikeshed.collections.associative.*
import borg.trikeshed.ipns.*
import borg.trikeshed.job.sha256
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.*
import kotlin.test.*

/**
 * storage.rs Store over the userspace uring facade on a directory holding the files the Rust Store
 * wrote (LoomStoreVectors): open, read, verify, lock and write back.
 */
class LoomStoreFileTest {
    val crypto: IpnsCrypto = Ed25519
    val ring = Config.ring()
    val dir = "${java.lang.System.getProperty("java.io.tmpdir").trimEnd('/')}/loom-store-${now()}-${(0..9999).random()}"

    @AfterTest
    fun close() = ring.closeNow()

    /** The captured store under [dir]: its 0700 directory, 0600 files and the 0600 configuration. */
    fun materialize(json: String, files: Series<Join<String, String>>): String {
        assertTrue(ring.create_dir(dir, 0x1ed))
        val store = "$dir/store"
        assertTrue(ring.create_dir(store, 0x1c0))
        for ((name, hex) in files.view) write("$store/$name", hex.hexToByteArray(), 0x180)
        val path = "$dir/config.json"
        write(path, json.replace("@STORE@", store).replace("@KEY@", "$dir/key").encodeToByteArray(), 0x180)
        return path
    }

    fun write(path: String, bytes: ByteArray, mode: Int) {
        val fd = ring.open(path, UringOp.O_WRONLY or UringOp.O_CREAT or UringOp.O_EXCL, mode)
        try {
            ring.write_all(fd, bytes)
        } finally {
            ring.close(fd)
        }
    }

    fun producers(config: Config): Map<String, ByteArray> =
        config.members.view.toList().filter { it.has(Role.Producer) }.associate { it.id to it.key() }

    @Test
    fun theRustStoreOpensReadsAndTakesWrites() {
        val config = Config.read(materialize(LoomStoreVectors.CONFIG_A, LoomStoreVectors.STORE_A))
        val records = LoomStoreVectors.STORE_A.view.filter { it.a.length == 69 }.map { Entry.decode(it.b.hexToByteArray()) }
        Store.open_config(config, producers(config), crypto).use { store ->
            assertEquals(records.filter { it.segment != null }.map { it.id.toHexString() }.sorted(), store.ids().map { it.toHexString() })
            for (record in records) {
                assertTrue(store.has_record(record.id))
                val segment = record.segment
                if (segment == null) assertEquals("segment archived and deleted", assertFailsWith<IllegalStateException> { store.get(record.id) }.message)
                else assertContentEquals(segment.to_bytes(), store.get(record.id).to_bytes())
                assertContentEquals(record.settlement?.to_bytes(), store.settlement(record.id)?.to_bytes())
                assertContentEquals(record.handoff?.to_bytes(), store.handoff(record.id)?.to_bytes())
            }
            assertEquals(listOf("fees", "orders"), store.ledger_streams())
            for (stream in store.ledger_streams()) assertEquals(
                LoomStoreVectors.STORE_A.view.first { it.a == ".ledger-$stream.cbor" }.b,
                store.ledger(stream)!!.encode(stream).toHexString(),
            )
            val replay = load_replay(store, config)
            assertEquals(setOf("authority", "producer"), replay.keys.map { it.first }.toSet())
            assertEquals(setOf(4_000_000_000uL, 4_000_000_001uL), replay.values.toSet())
            store.verify_records()
            assertEquals("store already owned", assertFailsWith<IllegalStateException> { Store.open_config(config, producers(config), crypto) }.message)
            val segment = Segment.sign("producer", 9uL, 1uL, ByteArray(48) { it.toByte() }, ByteArray(32) { 5 }, crypto)
            val id = store.ingest(segment)
            assertContentEquals(id, store.ingest(segment))
            store.save_replay(Cbor.encode(itemArrayOf(Item.Str("loom-replay/v1"), itemArrayOf(), Item.Bin(sha256(Cbor.encode(itemArrayOf()))))))
        }
        Store.open_config(config, producers(config), crypto).use { store ->
            assertEquals(3, store.ids().size)
            assertTrue(load_replay(store, config).isEmpty())
            store.verify_records()
        }
    }

    @Test
    fun retirementMetadataIsRefused() {
        val config = Config.read(materialize(LoomStoreVectors.CONFIG_B, LoomStoreVectors.STORE_B))
        Store.open_config(config, producers(config), crypto).use { store ->
            val id = store.ids().single()
            write("${config.store}/.retired.cbor", byteArrayOf(0), 0x180)
            assertEquals("unauthorized retirement metadata; recovery required", assertFailsWith<IllegalStateException> { store.verify_records() }.message)
            assertContentEquals(id, store.get(id).id())
        }
        assertEquals("unauthorized retirement metadata; recovery required",
            assertFailsWith<IllegalStateException> { Store.open_config(config, producers(config), crypto) }.message)
    }

    @Test
    fun anUnprotectedConfigurationIsRefused() {
        assertTrue(ring.create_dir(dir, 0x1ed))
        write("$dir/open.json", "{}".encodeToByteArray(), 0x1a4)
        assertEquals("unprotected configuration or key file", assertFailsWith<IllegalStateException> { Config.read("$dir/open.json") }.message)
        assertEquals("storage I/O failure", assertFailsWith<IllegalStateException> { Config.read("$dir/missing.json") }.message)
    }
}
