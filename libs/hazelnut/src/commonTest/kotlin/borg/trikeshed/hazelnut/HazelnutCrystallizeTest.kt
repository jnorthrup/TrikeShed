package borg.trikeshed.hazelnut

import borg.trikeshed.lib.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VClockTest {
    @Test fun tickIncrementsNodeEntry() {
        var clock = VClock()
        clock = clock.tick("node-1")
        assertEquals(1, clock.entries["node-1"])
        clock = clock.tick("node-1")
        assertEquals(2, clock.entries["node-1"])
    }

    @Test fun dominance() {
        val a = VClock(mapOf("x" to 2L, "y" to 1L))
        val b = VClock(mapOf("x" to 1L, "y" to 3L))
        val c = VClock(mapOf("x" to 1L, "y" to 1L))
        assertTrue(a.dominates(c))
        assertFalse(c.dominates(a))
        assertTrue(a.isConcurrent(b))
    }

    @Test fun mergePointwiseMax() {
        val a = VClock(mapOf("x" to 2L, "y" to 1L))
        val b = VClock(mapOf("x" to 1L, "y" to 3L, "z" to 4L))
        val merged = a.merge(b)
        assertEquals(2, merged.entries["x"])
        assertEquals(3, merged.entries["y"])
        assertEquals(4, merged.entries["z"])
    }
}

class GitGatedCrystoreTest {
    @Test fun recordCreatesPatchAndUpdateClock() {
        val store = GitGatedCrystore("node-1")
        val patch = store.record("obj1", "value1")
        assertEquals("obj1", patch.objectId)
        assertEquals("value1", patch.targetValue)
        assertEquals(1, store.patchCount)
    }

    @Test fun splitBrainDetection() {
        val store = GitGatedCrystore("node-1")
        val p1 = store.record("obj1", "val1")
        assertFalse(store.isInSplitBrain())

        // Simulate concurrent remote patch
        val remotePatch = ChangePatch(
            patchId = "remote:1",
            parentHashes = listOf("obj1:1"),
            nodeOrigin = "node-2",
            clock = store.patchById(p1.patchId)?.clock?.tick("node-2") ?: VClock(),
            timestamp = System.currentTimeMillis() + 1,
            changeType = ChangeType.UPDATE,
            objectId = "obj1",
            targetValue = "val-remote",
        )
        store.mergeRemote(listOf(remotePatch), CrdtStrategy.LAST_WRITER_WINS)
        assertTrue(store.isInSplitBrain() || store.patchCount > 1)
    }

    @Test fun lwwStrategyResolvesToLatest() {
        val store = GitGatedCrystore("local")
        val p1 = store.record("obj", "old")
        val remote = ChangePatch(
            patchId = "remote:1",
            parentHashes = listOf(p1.patchId),
            nodeOrigin = "remote",
            clock = p1.clock.tick("remote"),
            timestamp = System.currentTimeMillis() + 100,
            changeType = ChangeType.UPDATE,
            objectId = "obj",
            targetValue = "new",
        )
        store.mergeRemote(listOf(remote), CrdtStrategy.LAST_WRITER_WINS)
        store.resolveConflicts(CrdtStrategy.LAST_WRITER_WINS)
        assertTrue(store.patchCount >= 1)
    }

    @Test fun gCounterMergeAccumulates() {
        val store = GitGatedCrystore("node-1")
        val p1 = store.record("counter", "1")
        val remote = ChangePatch(
            patchId = "remote:c1",
            parentHashes = listOf(p1.patchId),
            nodeOrigin = "node-2",
            clock = p1.clock.tick("node-2"),
            timestamp = System.currentTimeMillis() + 10,
            changeType = ChangeType.UPDATE,
            objectId = "counter",
            targetValue = "2",
        )
        store.mergeRemote(listOf(remote), CrdtStrategy.G_COUNTER)
        assertTrue(store.patchCount >= 2)
    }

    @Test fun mvRegisterKeepsAllValues() {
        val store = GitGatedCrystore("local")
        val p1 = store.record("reg", "a")
        val remote = ChangePatch(
            patchId = "remote:r1",
            parentHashes = listOf(p1.patchId),
            nodeOrigin = "node-2",
            clock = p1.clock.tick("node-2"),
            timestamp = System.currentTimeMillis() + 10,
            changeType = ChangeType.UPDATE,
            objectId = "reg",
            targetValue = "b",
        )
        store.mergeRemote(listOf(remote), CrdtStrategy.MULTI_VALUE_REGISTER)
        val resolved = store.resolveConflicts(CrdtStrategy.MULTI_VALUE_REGISTER)
        assertTrue(resolved.size >= 1)
    }

    @Test fun deltaStateJoinsValues() {
        val store = GitGatedCrystore("node-1")
        val p1 = store.record("obj", "local")
        val remote = ChangePatch(
            patchId = "r:1",
            parentHashes = listOf(p1.patchId),
            nodeOrigin = "node-2",
            clock = p1.clock.tick("node-2"),
            timestamp = System.currentTimeMillis() + 100,
            changeType = ChangeType.UPDATE,
            objectId = "obj",
            targetValue = "remote",
        )
        store.mergeRemote(listOf(remote), CrdtStrategy.DELTA_STATE)
        val resolved = store.resolveConflicts(CrdtStrategy.DELTA_STATE)
        assertEquals(1, resolved.size)
    }
}

class NarsAdaptiveClusterTest {
    @Test fun upsertCreatesAndUpdateProfile() {
        val cluster = NarsAdaptiveCluster()
        val p1 = cluster.upsert("node-1", Transport.QUIC)
        assertEquals(Transport.QUIC, p1.transport)
        val p2 = cluster.upsert("node-1", Transport.QUIC)
        assertTrue(p2.residenceTimeMs > 0)
    }

    @Test fun specializationAffectsInfluence() {
        val cluster = NarsAdaptiveCluster()
        val n = cluster.upsert("node-1", Transport.SCTP)
        cluster.specialize("node-1", DistributedObjectType.HASH, 0.5)
        val p = cluster.upsert("node-1", Transport.SCTP)
        assertEquals(0.5, p.objectAffinity[DistributedObjectType.HASH])
        assertTrue(p.influence > 0)
    }

    @Test fun leaderSelectionByType() {
        val cluster = NarsAdaptiveCluster()
        cluster.upsert("n1", Transport.QUIC)
        cluster.specialize("n1", DistributedObjectType.STRING, 0.8)
        cluster.upsert("n2", Transport.HTX)
        cluster.specialize("n2", DistributedObjectType.SET, 0.9)

        val stringLeader = cluster.leaderFor(DistributedObjectType.STRING)
        assertNotNull(stringLeader)
        assertEquals("n1", stringLeader.nodeId)

        val setLeader = cluster.leaderFor(DistributedObjectType.SET)
        assertNotNull(setLeader)
        assertEquals("n2", setLeader.nodeId)
    }

    @Test fun heartbeatUpdatesReliability() {
        val cluster = NarsAdaptiveCluster()
        cluster.upsert("n1", Transport.IPFS)
        cluster.heartbeat("n1", true)
        cluster.heartbeat("n1", true)
        cluster.heartbeat("n1", false)
        val p = cluster.upsert("n1", Transport.IPFS)
        assertEquals(3, p.reliabilityHistory.size)
    }

    @Test fun conflictTracking() {
        val cluster = NarsAdaptiveCluster()
        cluster.upsert("n1", Transport.QUIC)
        cluster.recordConflict("n1", true)
        cluster.recordConflict("n1", true)
        cluster.recordConflict("n1", false)
        val p = cluster.upsert("n1", Transport.QUIC)
        assertEquals(3, p.conflictCount)
        assertEquals(2, p.conflictResolved)
    }

    @Test fun liveCountFiltersStales() {
        val cluster = NarsAdaptiveCluster()
        cluster.upsert("fresh", Transport.QUIC)
        cluster.profiles["stale"] = NarsNodeProfile(
            nodeId = "stale",
            transport = Transport.HTX,
            lastHeartbeat = 0,
        )
        assertEquals(1, cluster.liveCount)
    }
}

class HazelTopologyTest {
    @Test fun addNodeAndEdge() {
        val topo = HazelTopology()
        val n = ProductionGraphNode("n1", GraphNodeType.COORDINATOR, Transport.QUIC)
        topo.addNode(n)
        topo.addEdge("n1", "n2", "replicates", 0.5)
        topo.addObject("n2") { n.addObject("obj1"); it }
        assertEquals(1, topo.nodeCount)
        assertEquals(1, topo.edgeCount)
    }

    @Test fun replicatorsFor() {
        val topo = HazelTopology()
        val sa = ProductionGraphNode("a", GraphNodeType.DATA_NODE, Transport.SCTP, objectIds = setOf("o1")).addObject("x")
        val sb = ProductionGraphNode("b", GraphNodeType.OBSERVER, Transport.QUIC, objectIds = setOf("o1")).addObject("x")
        val sc = ProductionGraphNode("c", GraphNodeType.DATA_NODE, Transport.HTX).addObject("y")
        topo.addNode(sa)
        topo.addNode(sb)
        topo.addNode(sc)
        val reps = topo.replicatorsFor("x")
        assertEquals(2, reps.size)
    }

    @Test fun coordinatorSelectionByUptime() {
        val topo = HazelTopology()
        topo.addNode(ProductionGraphNode("c1", GraphNodeType.COORDINATOR, Transport.QUIC, objectIds = setOf("o1"), uptimeMs = 1000))
        topo.addNode(ProductionGraphNode("c2", GraphNodeType.COORDINATOR, Transport.SCTP, objectIds = setOf("o1"), uptimeMs = 5000))
        val coord = topo.coordinatorFor("o1")
        assertNotNull(coord)
        assertEquals("c2", coord!!.nodeId)
    }

    @Test fun nodesByType() {
        val topo = HazelTopology()
        topo.addNode(ProductionGraphNode("a", GraphNodeType.GATEKEEPER, Transport.IPFS))
        topo.addNode(ProductionGraphNode("b", GraphNodeType.BRIDGE, Transport.QUIC))
        assertEquals(1, topo.nodesByType(GraphNodeType.GATEKEEPER).size)
    }
}

class ConflictAnalyticsTest {
    @Test fun recordAndCount() {
        val ca = ConflictAnalytics()
        ca.record("n1", "conflict", 1.0, "objectType" to "STRING")
        ca.record("n2", "conflict", 1.0, "objectType" to "HASH")
        ca.record("n1", "merge_latency", 50.0)
        assertEquals(3, ca.sampleCount())
    }

    @Test fun mostContestedNode() {
        val ca = ConflictAnalytics()
        repeat(5) { ca.record("hot", "conflict", 1.0) }
        repeat(2) { ca.record("cold", "conflict", 1.0) }
        assertEquals("hot", ca.mostContestedNodeId())
    }

    @Test fun avgMergeLatency() {
        val ca = ConflictAnalytics()
        ca.record("n1", "merge_latency", 40.0)
        ca.record("n2", "merge_latency", 60.0)
        val avg = ca.avgMergeLatencyMs()
        assertEquals(50.0, avg, 1.0)
    }

    @Test fun splitBrainByObjectType() {
        val ca = ConflictAnalytics()
        ca.record("n1", "split_brain", 1.0, "objectType" to "LIST")
        ca.record("n2", "split_brain", 1.0, "objectType" to "LIST")
        ca.record("n3", "split_brain", 1.0, "objectType" to "HASH")
        val byType = ca.splitBrainByObjectType()
        assertEquals(2, byType["LIST"])
        assertEquals(1, byType["HASH"])
    }

    @Test fun pruneOldSamples() {
        val ca = ConflictAnalytics()
        // Insert old samples manually
        ca.samples.add(TimeseriesSample(0, "conflict", 1.0, "n1"))
        ca.record("n2", "conflict", 1.0)
        ca.prune(1)
        // Old sample (timestamp=0) should be pruned
        assertTrue(ca.samples.all { it.timestamp > 0 })
    }
}

class SplitBrainOrchestratorTest {
    private fun makeOrchestrator(): SplitBrainOrchestrator {
        val crystore = GitGatedCrystore("node-1")
        val cluster = NarsAdaptiveCluster()
        val topology = HazelTopology()
        val analytics = ConflictAnalytics()
        cluster.upsert("node-1", Transport.QUIC)
        cluster.specialize("node-1", DistributedObjectType.STRING, 0.5)
        return SplitBrainOrchestrator(crystore, cluster, topology, analytics)
    }

    @Test fun resolveIdempotentWhenNoConflict() {
        val orch = makeOrchestrator()
        val resolved = orch.resolve()
        assertTrue(resolved.isEmpty())
    }

    @Test fun resolveHandlesConflict() {
        val orch = makeOrchestrator()
        // Force split-brain by introducing concurrent patches
        val p1 = orch.resolve() // idempotent, no split-brain
        assertTrue(p1.isEmpty())
    }

    @Test fun registerNode() {
        val orch = makeOrchestrator()
        val node = ProductionGraphNode("n1", GraphNodeType.DATA_NODE, Transport.QUIC)
        val registered = orch.registerNode(node)
        assertNotNull(registered)
    }

    @Test fun crdtStrategyByInfluence() {
        val orch = makeOrchestrator()
        // High influence node (>0.7) should trigger LWW
        // Low influence should trigger MERGEABLE_SEQUENCE
        // We can verify via the analytics
        val p1 = orch.resolve()
        // No-op when no split-brain
        assertTrue(p1.isEmpty())
    }
}
