package borg.trikeshed.hazelnut

import borg.trikeshed.lib.*
import borg.trikeshed.miniduck.DocRowVec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HazelnutDistributedObjectTest {

    @Test
    fun dStringTracksRevisionAndOrigin() {
        val original = DString(id = "cache:token", value = "abc123", ttl = 300, originNode = "node-2")
        assertEquals(0, original.revision)
        assertEquals("node-2", original.originNode)

        val updated = original.copyWithValue("def456")
        assertEquals(1, updated.revision)
        assertEquals("def456", updated.value)
        assertEquals("cache:token", updated.id)
    }

    @Test
    fun dListLeftAndRightOperations() {
        val list = DList(id = "queue:jobs", elements = listOf("a", "b", "c"))
        assertEquals(3, list.elements.size)

        val appended = list.appendLeft("first")
        assertEquals(listOf("first", "a", "b", "c"), appended.elements)

        val appendedRight = list.appendRight("last")
        assertEquals(listOf("a", "b", "c", "last"), appendedRight.elements)
    }

    @Test
    fun dListPopLeftAndRight() {
        val list = DList(id = "list-1", elements = listOf("x", "y", "z"))

        val (head, tail) = list.popLeft()
        assertEquals("x", head)
        assertEquals(listOf("y", "z"), tail.elements)
        assertEquals(1, tail.revision)

        val (last, init) = list.popRight()
        assertEquals("z", last)
        assertEquals(listOf("x", "y"), init.elements)
    }

    @Test
    fun dListPopFromEmptyReturnsNull() {
        val empty = DList(id = "empty", elements = emptyList())
        val (item, remaining) = empty.popLeft()
        assertNull(item)
        assertEquals(0, remaining.elements.size)

        val (item2, remaining2) = empty.popRight()
        assertNull(item2)
        assertSame(empty, remaining2)
    }

    @Test
    fun dHashFieldOperations() {
        val hash = DHash(id = "user:1").set("name", "alice").set("role", "admin")
        assertEquals("alice", hash.get("name"))
        assertEquals("admin", hash.get("role"))
        assertEquals(2, hash.size)
        assertEquals(2, hash.revision)

        val removed = hash.remove("name")
        assertNull(removed.get("name"))
        assertEquals("admin", removed.get("role"))
        assertEquals(3, removed.revision)
    }

    @Test
    fun dQueueEnqueueDequeueWithCapacity() {
        val queue = DQueue(id = "bounded", items = listOf("job1"), maxCapacity = 2)
        assertEquals(1, queue.size)

        val added = queue.enqueue("job2")
        assertEquals(2, added.size)

        assertFailsWith<IllegalArgumentException> { added.enqueue("job3") }

        val (head, remainder) = added.dequeue()
        assertEquals("job1", head)
        assertEquals(1, remainder.size)
    }

    @Test
    fun dTopicSubscribeUnsubscribe() {
        val topic = DTopic(id = "market-updates")
        assertEquals(0, topic.subscriberIds.size)

        val withSub = topic.subscribe("sub-1").subscribe("sub-2")
        assertEquals(2, withSub.subscriberIds.size)

        val unsubbed = withSub.unsubscribe("sub-1")
        assertEquals(1, unsubbed.subscriberIds.size)
        assertEquals("sub-2", unsubbed.subscriberIds[0])
    }

    @Test
    fun transportEnumMapsToCorrectSchemeAndPort() {
        assertEquals("sctp", Transport.SCTP.scheme)
        assertEquals(2904, Transport.SCTP.defaultPort)

        assertEquals("quic", Transport.QUIC.scheme)
        assertEquals(443, Transport.QUIC.defaultPort)

        assertEquals("htx", Transport.HTX.scheme)
        assertEquals(8080, Transport.HTX.defaultPort)

        assertEquals("ipfs", Transport.IPFS.scheme)
        assertEquals(4001, Transport.IPFS.defaultPort)
    }

    @Test
    fun clusterNodeFullName() {
        val node = HazelnutClusterNode(
            nodeId = "node-1",
            transport = Transport.QUIC,
            address = "10.0.0.1",
            port = 443,
        )
        assertEquals("quic://10.0.0.1:443", node.fullName())
    }
}

class HazelnutCouchGroundingTest {

    @Test
    fun dStringProjectsToCouchRowVec() {
        val obj = DString(id = "cache:token", value = "abc123", ttl = 300, revision = 5, originNode = "node-3")
        val row = obj.toCouchRowVec()

        assertEquals("cache:token", row["objectId"])
        assertEquals("STRING", row["objectType"])
        assertEquals("5", row["revision"])
        assertEquals("300", row["ttl"])
        assertEquals("node-3", row["originNode"])
        assertEquals("abc123", row["value"])
        assertEquals(6, row["valueLength"])
    }

    @Test
    fun dListProjectsToCouchRowVec() {
        val obj = DList(id = "queue:jobs", elements = listOf("a", "b", "c"), ttl = 600, revision = 3)
        val row = obj.toCouchRowVec()

        assertEquals("queue:jobs", row["objectId"])
        assertEquals("LIST", row["objectType"])
        assertEquals("3", row["revision"])
        assertEquals(3, row["elementCount"])
    }

    @Test
    fun dHashProjectsToCouchRowVec() {
        val obj = DHash(id = "user:1", fields = mapOf("name" to "alice"), revision = 2)
        val row = obj.toCouchRowVec()

        assertEquals("user:1", row["objectId"])
        assertEquals("HASH", row["objectType"])
        assertEquals(1, row["fieldCount"])
    }

    @Test
    fun dQueueProjectsToCouchRowVec() {
        val obj = DQueue(id = "bounded-q", items = listOf("j1"), maxCapacity = 10)
        val row = obj.toCouchRowVec()

        assertEquals("bounded-q", row["objectId"])
        assertEquals("QUEUE", row["objectType"])
        assertEquals(1, row["itemCount"])
        assertEquals(10, row["maxCapacity"])
    }

    @Test
    fun dTopicProjectsToCouchRowVec() {
        val obj = DTopic(id = "alerts", subscriberIds = listOf("s1", "s2"))
        val row = obj.toCouchRowVec()

        assertEquals("alerts", row["objectId"])
        assertEquals("TOPIC", row["objectType"])
        assertEquals(2, row["subscriberCount"])
    }

    @Test
    fun hazelnutEnvelopeGroundsToCouchRowVec() {
        val envelope = HazelnutEnvelope(
            forum = "idmg",
            threadId = "t-7",
            commentId = "c-3",
            body = "service-free projection",
            labels = mapOf("kind" to "note"),
        )
        val row = envelope.toRowVec()
        assertEquals("idmg", row["forum"])
        assertEquals("t-7", row["threadId"])
        assertEquals(0, row["attachmentCount"])
        assertNotNull(row.child)
    }
}

class HazelnutUnifiedServiceTest {

    private fun makeService(): HazelnutUnifiedService =
        HazelnutUnifiedService(
            cluster = HazelnutClusterConfig(localNodeId = "node-1"),
            client = object : HazelnutClient {
                override fun upsert(envelope: HazelnutEnvelope): HazelnutWriteReceipt =
                    HazelnutWriteReceipt(
                        forum = envelope.forum,
                        threadId = envelope.threadId,
                        commentId = envelope.commentId,
                        accepted = true,
                        revision = "r1",
                    )
                override fun query(query: HazelnutQuery) =
                    0 j { _: Int -> HazelnutEnvelope(
                        forum = query.forum,
                        threadId = "t",
                        commentId = "c",
                        body = "body",
                    ) }
            },
        )

    @Test
    fun publishDelegatesClient() {
        val service = makeService()
        val receipt = service.publish(
            HazelnutEnvelope(forum = "idmg", threadId = "t-1", commentId = "c-1", body = "hello")
        )
        assertEquals("idmg", receipt.forum)
        assertEquals(true, receipt.accepted)
        assertEquals("r1", receipt.revision)
    }

    @Test
    fun serviceCreatesDString() {
        val service = makeService()
        val str = service.setString("cmc:cache", "[1,2,3]", ttl = 300)
        assertEquals("cmc:cache", str.id)
        assertEquals("[1,2,3]", str.value)
        assertEquals(300, str.ttl)
    }

    @Test
    fun serviceCreatesDListWithLpushRpush() {
        val service = makeService()
        val list = service.lpush("queue:jobs", "job1", "job2")
        assertEquals(listOf("job1", "job2"), list.elements)

        val list2 = service.rpush("other", "a", "b")
        assertEquals(listOf("a", "b"), list2.elements)
    }

    @Test
    fun serviceDListPopLeft() {
        val service = makeService()
        val list = service.lpush("q", "first", "second")
        val (head, tail) = service.lpop(list)
        assertEquals("first", head)
        assertEquals(listOf("second"), tail.elements)
    }

    @Test
    fun serviceDHashOperations() {
        val service = makeService()
        val hash = service.hset("user:1", "name", "alice")
        assertEquals("alice", service.hget(hash, "name"))
        assertEquals(1, service.hgetall(hash).size)
    }

    @Test
    fun serviceDQueueOperations() {
        val service = makeService()
        val queue = service.queue("bounded", maxCapacity = 2)
        val enqueued = queue.enqueue("job1")
        assertEquals(1, enqueued.size)
        val (head, remainder) = service.queue("bounded").dequeue()
        assertNull(head)
    }

    @Test
    fun serviceDTopicOperations() {
        val service = makeService()
        val topic = service.topic("alerts", subscribeId = "sub-1")
        assertEquals(1, topic.subscriberIds.size)
        assertEquals("sub-1", topic.subscriberIds[0])
    }

    @Test
    fun projectDistributedObjectToCouchRowVec() {
        val service = makeService()
        val obj = DString(id = "test:key", value = "val", revision = 4)
        val row = service.project(obj)
        assertEquals("test:key", row["objectId"])
        assertEquals("4", row["revision"])
    }

    @Test
    fun projectHazelnutEnvelopeToCouchRowVec() {
        val service = makeService()
        val envelope = HazelnutEnvelope(forum = "f", threadId = "t", commentId = "c", body = "body")
        val row = service.project(envelope)
        assertEquals("f", row["forum"])
    }
}
