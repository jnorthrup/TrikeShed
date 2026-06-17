package borg.trikeshed.forge

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * TDD RED tests for Operational Cascade detection and execution.
 * These tests define the expected behavior for cascades.
 */
class ForgeCascadeTddTest {

    private val workspace: ForgeWorkspace = ForgeWorkspaceImpl()

    @Test
    fun `detectCascades finds time-series hierarchy from JSONL data`() = runTest {
        // Given: JSONL with infrastructure_id, machine_id, timestamp, metrics
        val jsonl = """
            {"infrastructure_id": "infra-1", "machine_id": "m-1", "ts": "2024-01-01T10:00:00", "cpu_mhz": 2400, "memory_mib": 8192}
            {"infrastructure_id": "infra-1", "machine_id": "m-1", "ts": "2024-01-01T11:00:00", "cpu_mhz": 2500, "memory_mib": 8192}
            {"infrastructure_id": "infra-1", "machine_id": "m-2", "ts": "2024-01-01T10:00:00", "cpu_mhz": 3200, "memory_mib": 16384}
            {"infrastructure_id": "infra-2", "machine_id": "m-3", "ts": "2024-01-01T10:00:00", "cpu_mhz": 2800, "memory_mib": 16384}
        """.trimIndent()

        val file = ForgeFile(
            id = ForgeFileId("readings.jsonl"),
            path = "data/readings.jsonl",
            content = jsonl,
            mimeType = "application/jsonl"
        )
        workspace.put(file)

        val request = CascadeDetectionRequest(
            sources = listOf(CascadeSource.FileSource(file.id, DataFormat.JSON)),
            candidateKeys = listOf("infrastructure_id", "machine_id", "ts"),
            candidateMetrics = listOf("cpu_mhz", "memory_mib"),
            maxHierarchyDepth = 4
        )

        val result = workspace.detectCascades(request)

        // Then: Should detect a cascade with key hierarchy [infrastructure_id, machine_id, ts]
        assertTrue(result.detectedCascades.isNotEmpty())
        val cascade = result.detectedCascades[0]
        assertEquals(listOf("infrastructure_id", "machine_id", "ts"), cascade.keyHierarchy)
        assertTrue(result.inferredKeyHierarchies.contains(listOf("infrastructure_id", "machine_id", "ts")))
        assertTrue(result.inferredMetrics.containsAll(listOf("cpu_mhz", "memory_mib")))
        assertTrue(result.confidence > 0.7)
    }

    @Test
    fun `detectCascades produces executable cascade with MapStage ReduceStage RereduceStage`() = runTest {
        val jsonl = """
            {"category": "A", "amount": 10, "region": "US"}
            {"category": "A", "amount": 20, "region": "EU"}
            {"category": "B", "amount": 5, "region": "US"}
        """.trimIndent()

        val file = ForgeFile(ForgeFileId("data.jsonl"), "data.jsonl", jsonl, "application/jsonl")
        workspace.put(file)

        val request = CascadeDetectionRequest(
            sources = listOf(CascadeSource.FileSource(file.id)),
            candidateKeys = listOf("category", "region"),
            candidateMetrics = listOf("amount")
        )

        val result = workspace.detectCascades(request)

        val cascade = result.detectedCascades[0]
        assertEquals(3, cascade.stages.size)  // MapStage, ReduceStage, RereduceStage
        val mapStage = cascade.stages[0] as CascadeStage.MapStage
        val reduceStage = cascade.stages[1] as CascadeStage.ReduceStage
        val rereduceStage = cascade.stages[2] as CascadeStage.RereduceStage

        assertTrue(mapStage.transform is MapTransform.JsFunction)
        assertTrue(reduceStage.reduceFn is ReduceTransform.Builtin)
        assertEquals(BuiltinReduce.SUM, (reduceStage.reduceFn as ReduceTransform.Builtin).kind)
    }

    @Test
    fun `executeCascadeSync runs map-reduce-rereduce and returns condensed rows`() = runTest {
        val jsonl = """
            {"category": "A", "count": 10}
            {"category": "A", "count": 20}
            {"category": "B", "count": 5}
        """.trimIndent()

        val file = ForgeFile(ForgeFileId("data.jsonl"), "data.jsonl", jsonl, "application/jsonl")
        workspace.put(file)

        val request = CascadeDetectionRequest(
            sources = listOf(CascadeSource.FileSource(file.id)),
            candidateKeys = listOf("category", "count"),
            candidateMetrics = listOf("count")
        )
        val detection = workspace.detectCascades(request)
        val cascade = detection.detectedCascades[0]
        workspace.putCascade(cascade)

        val result = workspace.executeCascadeSync(cascade.id)

        assertEquals(CascadeExecutionStatus.SUCCESS, result.status)
        assertEquals(2, result.output.size)  // A and B

        val aRow = result.output.first { it.key == listOf("A") }
        val bRow = result.output.first { it.key == listOf("B") }
        // The stub implementation returns JSON with sum/avg/min/max
        assertTrue(aRow.value.contains("\"count_sum\":\"30.0\""))  // sum of 10 + 20
        assertTrue(bRow.value.contains("\"count_sum\":\"5.0\""))
    }

    @Test
    fun `cascadeExecutionAsWorkflowStep integrates with workflow DAG`() = runTest {
        val jsonl = """
            {"name": "Alice", "dept": "Eng", "score": 90}
            {"name": "Bob", "dept": "Eng", "score": 80}
            {"name": "Carol", "dept": "Sales", "score": 95}
        """.trimIndent()

        val file = ForgeFile(ForgeFileId("scores.jsonl"), "scores.jsonl", jsonl, "application/jsonl")
        workspace.put(file)

        val request = CascadeDetectionRequest(
            sources = listOf(CascadeSource.FileSource(file.id)),
            candidateKeys = listOf("dept"),
            candidateMetrics = listOf("score")
        )
        val detection = workspace.detectCascades(request)
        val cascade = detection.detectedCascades[0]
        workspace.putCascade(cascade)

        val workflow = ForgeWorkflow(
            id = ForgeWorkflowId("cascade-wf"),
            name = "Dept Score Rollup",
            steps = listOf(
                WorkflowStep.CascadeExecution("rollup", cascade.id, emptyMap())
            ),
            inputSchema = emptyMap(),
            outputSchema = mapOf("rollup" to "json")
        )
        workspace.putWorkflow(workflow)
        workspace.snapshot("initial")

        val result = workspace.executeSync(ForgeWorkflowId("cascade-wf"), emptyMap())

        assertEquals(ExecutionStatus.SUCCESS, result.status)
        // Mock implementation returns inputs as finalOutputs; cascade step produces artifacts
    }

    @Test
    fun `getCascadeGraph returns visualization nodes and edges`() = runTest {
        val jsonl = """
            {"a": 1, "b": 2, "metric": 100}
        """.trimIndent()

        val file = ForgeFile(ForgeFileId("simple.jsonl"), "simple.jsonl", jsonl, "application/jsonl")
        workspace.put(file)

        val request = CascadeDetectionRequest(
            sources = listOf(CascadeSource.FileSource(file.id)),
            candidateKeys = listOf("a", "b"),
            candidateMetrics = listOf("metric")
        )
        val detection = workspace.detectCascades(request)
        val cascade = detection.detectedCascades[0]
        workspace.putCascade(cascade)

        val graph = workspace.getCascadeGraph(cascade.id)

        assertNotNull(graph)
        assertEquals(5, graph!!.nodes.size)  // SOURCE -> MAP -> REDUCE -> REREDUCE -> SINK
        assertEquals(4, graph.edges.size)
        val types = graph.nodes.map { it.type }.toSet()
        assertTrue(types.contains(CascadeStageType.SOURCE))
        assertTrue(types.contains(CascadeStageType.MAP))
        assertTrue(types.contains(CascadeStageType.REDUCE))
        assertTrue(types.contains(CascadeStageType.REREDUCE))
    }

    @Test
    fun `cascadeProgress stream emits stage events`() = runTest {
        val jsonl = """
            {"k": "x", "v": 1}
            {"k": "x", "v": 2}
        """.trimIndent()

        val file = ForgeFile(ForgeFileId("progress.jsonl"), "progress.jsonl", jsonl, "application/jsonl")
        workspace.put(file)

        val request = CascadeDetectionRequest(
            sources = listOf(CascadeSource.FileSource(file.id)),
            candidateKeys = listOf("k"),
            candidateMetrics = listOf("v")
        )
        val detection = workspace.detectCascades(request)
        val cascade = detection.detectedCascades[0]
        workspace.putCascade(cascade)

        val progressEvents = workspace.executeCascade(cascade.id).toList()

        assertTrue(progressEvents.any { it is CascadeProgress.StageStarted })
        assertTrue(progressEvents.any { it is CascadeProgress.StageCompleted })
        assertTrue(progressEvents.any { it is CascadeProgress.CascadeCompleted })
    }
}