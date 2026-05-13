package borg.trikeshed.hazelnut

import borg.trikeshed.lib.*
import borg.trikeshed.miniduck.DocRowVec
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HazelnutDistributedObjectTest {

    @Test fun dStringTracksRevisionAndOrigin() {
        val original = DString(id = "cache:token", value = "abc123", ttl = 300, originNode = "node-2")
        assertEquals(0, original.revision)
        assertEquals("node-2", original.originNode)
        val updated = original.copyWithValue("def456")
        assertEquals(1, updated.revision)
        assertEquals("def456", updated.value)
    }

    @Test fun dListLeftAndRightOperations() {
        val list = DList(id = "queue:jobs", elements = listOf("a", "b", "c"))
        assertEquals(3, list.elements.size)
        assertEquals(listOf("first", "a", "b", "c"), list.appendLeft("first").elements)
        assertEquals(listOf("a", "b", "c", "last"), list.appendRight("last").elements)
    }

    @Test fun dListPopLeftAndRight() {
        val list = DList(id = "list-1", elements = listOf("x", "y", "z"))
        val (head, tail) = list.popLeft()
        assertEquals("x", head)
        assertEquals(listOf("y", "z"), tail.elements)
        val (last, init) = list.popRight()
        assertEquals("z", last)
        assertEquals(listOf("x", "y"), init.elements)
    }

    @Test fun dListPopFromEmptyReturnsNull() {
        val empty = DList(id = "empty", elements = emptyList())
        val (item, remaining) = empty.popLeft()
        assertNull(item)
        assertSame(empty, remaining)
        val (item2, remaining2) = empty.popRight()
        assertNull(item2)
        assertSame(empty, remaining2)
    }

    @Test fun dHashFieldOperations() {
        val hash = DHash(id = "user:1").set("name", "alice").set("role", "admin")
        assertEquals("alice", hash.get("name"))
        assertEquals(2, hash.size)
        assertEquals(2, hash.revision)
        val removed = hash.remove("name")
        assertNull(removed.get("name"))
        assertEquals(1, removed.size)
    }

    @Test fun dSetAddRemove() {
        val set = DSet(id = "tags").add("red", "blue").add("green")
        assertEquals(3, set.size)
        val removed = set.remove("red")
        assertEquals(2, removed.size)
        assertTrue("blue" in removed.members)
    }

    @Test fun dSortedSetAddRemoveScoreRange() {
        val ss = DSortedSet(id = "leaderboard")
            .add("alice", 100.0).add("bob", 200.0).add("charlie", 150.0)
        assertEquals(3, ss.entries.size)
        assertEquals(100.0, ss.score("alice"))
        val range = ss.rangeByScore(100.0, 150.0)
        assertEquals(2, range.size)
        val removed = ss.remove("bob")
        assertEquals(2, removed.entries.size)
    }

    @Test fun dBitmapSetAndCount() {
        val bm = DBitmap(id = "bitfield").setBit(0, true).setBit(7, true)
        assertTrue(bm.bitAt(0))
        assertTrue(bm.bitAt(7))
        assertTrue(!bm.bitAt(4))
        assertEquals(1, bm.bytes.size)
        assertEquals(2, bm.populateCount())
    }

    @Test fun dGeoAddDistance() {
        val geo = DGeo(id = "places")
            .add(-74.0060, 40.7128, "NYC")
            .add(-0.1278, 51.5074, "LON")
        assertEquals("NYC", geo.find("NYC")?.member)
        val dist = geo.distance("NYC", "LON")
        assertNotNull(dist)
        assertTrue(dist > 5000000.0)
    }

    @Test fun dStreamAppendRangeLen() {
        val stream = DStream(id = "events")
            .append("1-0", mapOf("a" to "1"))
            .append("2-0", mapOf("b" to "2"))
        assertEquals(2, stream.len)
        val range = stream.range("1-0", "1-0")
        assertEquals(1, range.size)
    }

    @Test fun dStreamMaxLenTrim() {
        val stream = DStream(id = "trimmed", maxLen = 2)
            .append("1-0", mapOf())
            .append("2-0", mapOf())
            .append("3-0", mapOf())
        assertEquals(2, stream.entries.size)
        assertEquals("2-0", stream.entries[0].id)
    }

    @Test fun dHyperLogLogAddMergeCount() {
        val hll = DHyperLogLog(id = "uniq").add("a", "b", "c")
        assertTrue(hll.count() >= 3)
        val hll2 = DHyperLogLog(id = "uniq2").add("d", "e")
        val merged = hll.merge(hll2)
        assertTrue(merged.count() >= 3)
    }

    @Test fun transportEnumMapsToSchemePortOpcode() {
        assertEquals("sctp", Transport.SCTP.scheme)
        assertEquals(2904, Transport.SCTP.defaultPort)
        assertEquals("quic", Transport.QUIC.scheme)
        assertEquals(443, Transport.QUIC.defaultPort)
        assertEquals("htx", Transport.HTX.scheme)
        assertEquals(8080, Transport.HTX.defaultPort)
        assertEquals("ipfs", Transport.IPFS.scheme)
        assertEquals(4001, Transport.IPFS.defaultPort)
    }

    @Test fun clusterNodeFullName() {
        val node = HazelnutClusterNode(
            nodeId = "node-1", transport = Transport.QUIC, address = "10.0.0.1", port = 443,
        )
        assertEquals("quic://10.0.0.1:443", node.fullName())
    }
}

class HazelnutCouchGroundingTest {

    @Test fun dStringProjects() {
        val obj = DString(id = "cache:token", value = "abc123", ttl = 300, revision = 5, originNode = "node-3")
        val row = obj.toCouchRowVec()
        assertEquals("cache:token", row["objectId"])
        assertEquals("STRING", row["objectType"])
        assertEquals(5L, row["revision"])
        assertEquals("300", row["ttl"])
        assertEquals("abc123", row["value"])
        assertEquals(6, row["valueLength"])
    }

    @Test fun dListProjects() {
        val obj = DList(id = "queue:jobs", elements = listOf("a", "b", "c"), ttl = 600, revision = 3)
        val row = obj.toCouchRowVec()
        assertEquals("LIST", row["objectType"])
        assertEquals(3L, row["revision"])
        assertEquals(3, row["elementCount"])
    }

    @Test fun dHashProjects() {
        val obj = DHash(id = "user:1", fields = mapOf("name" to "alice"), revision = 2)
        val row = obj.toCouchRowVec()
        assertEquals("HASH", row["objectType"])
        assertEquals(1, row["fieldCount"])
    }

    @Test fun dSetProjects() {
        val obj = DSet(id = "tags", members = setOf("red", "blue", "green"), revision = 3)
        val row = obj.toCouchRowVec()
        assertEquals("SET", row["objectType"])
        assertEquals(3, row["memberCount"])
    }

    @Test fun dSortedSetProjects() {
        val obj = DSortedSet(id = "lb", entries = listOf(SortedSetEntry("a", 100.0)), revision = 1)
        val row = obj.toCouchRowVec()
        assertEquals("SORTED_SET", row["objectType"])
        assertEquals(1, row["entryCount"])
    }

    @Test fun dBitmapProjects() {
        val obj = DBitmap(id = "bits", bytes = byteArrayOf(0xFF.toByte()), revision = 2)
        val row = obj.toCouchRowVec()
        assertEquals("BITMAP", row["objectType"])
        assertEquals(1, row["byteSize"])
        assertEquals(8, row["populateCount"])
    }

    @Test fun dGeoProjects() {
        val obj = DGeo(id = "places", points = listOf(GeoPoint(1.0, 2.0, "x")), revision = 1)
        val row = obj.toCouchRowVec()
        assertEquals("GEO", row["objectType"])
        assertEquals(1, row["pointCount"])
    }

    @Test fun dStreamProjects() {
        val obj = DStream(id = "events", entries = listOf(StreamEntry("1-0", mapOf("a" to "1"))), maxLen = 1000)
        val row = obj.toCouchRowVec()
        assertEquals("STREAM", row["objectType"])
        assertEquals(1, row["entryCount"])
        assertEquals(1000, row["maxLen"])
    }

    @Test fun dHyperLogLogProjects() {
        val obj = DHyperLogLog(id = "hll", cardinality = 42, revision = 2)
        val row = obj.toCouchRowVec()
        assertEquals("HYPERLOGLOG", row["objectType"])
        assertEquals(42L, row["cardinality"])
    }

    @Test fun envelopeProjects() {
        val envelope = HazelnutEnvelope(
            forum = "idmg", threadId = "t-7", commentId = "c-3", body = "body", labels = mapOf("kind" to "note"),
        )
        val row = envelope.toRowVec()
        assertEquals("idmg", row["forum"])
        assertEquals(0, row["attachmentCount"])
        assertEquals(1, row["labelCount"])
        assertNotNull(row.child)
    }
}

class HazelnutUnifiedServiceTest {

    private fun makeService(): HazelnutUnifiedService =
        HazelnutUnifiedService(
            cluster = HazelnutClusterConfig(localNodeId = "node-1"),
            client = object : HazelnutClient {
                override fun upsert(e: HazelnutEnvelope) =
                    HazelnutWriteReceipt(e.forum, e.threadId, e.commentId, true, "r1")
                override fun query(q: HazelnutQuery) =
                    0 j { _: Int -> HazelnutEnvelope(forum = q.forum, threadId = "t", commentId = "c", body = "b") }
            },
        )

    @Test fun publishDelegates() {
        val s = makeService()
        val r = s.publish(HazelnutEnvelope(forum = "idmg", threadId = "t-1", commentId = "c-1", body = "hello"))
        assertEquals(true, r.accepted)
    }

    @Test fun serviceAllNineTypes() {
        val s = makeService()

        val str = s.setString("cmc:cache", "[1,2,3]", 300)
        assertEquals("[1,2,3]", str.value)

        val list = s.lpush("q", "job1", "job2")
        assertEquals(listOf("job1", "job2"), list.elements)

        val hash = s.hset("user:1", "name", "alice")
        assertEquals("alice", s.hget(hash, "name"))

        val set = s.sadd("tags", "red", "blue")
        assertEquals(2, set.size)
        val set2 = s.srem(set, "red")
        assertEquals(1, set2.size)

        val ss = s.zadd("lb", 100.0, "alice")
        assertEquals(100.0, ss.entries[0].score)

        val geo = s.geoadd("places", -74.0, 40.7, "NYC")
        assertNotNull(s.geodist(geo, "NYC", "NYC"))

        val stream = s.xadd("events", "1-0", mapOf("a" to "1"))
        assertEquals(1, stream.len)

        val hll = s.pfadd("hll", "a", "b")
        assertEquals(2, hll.cardinality)

        val hll2 = s.pfadd("hll2", "c")
        val merged = s.pfmerge(hll, hll2)
        assertTrue(merged.cardinality >= 2)
    }

    @Test fun projectDistributedObject() {
        val s = makeService()
        val obj = DString(id = "test:key", value = "val", revision = 4)
        val row = s.project(obj)
        assertEquals("test:key", row["objectId"])
        assertEquals(4L, row["revision"])
    }

    @Test fun projectHazelnutEnvelope() {
        val s = makeService()
        val e = HazelnutEnvelope(forum = "f", threadId = "t", commentId = "c", body = "body")
        val row = s.project(e)
        assertEquals("f", row["forum"])
    }
}
