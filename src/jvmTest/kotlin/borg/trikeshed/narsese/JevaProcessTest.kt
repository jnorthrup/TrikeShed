package borg.trikeshed.narsese

import borg.trikeshed.userspace.nio.channels.spi.JvmProcessOperations
import borg.trikeshed.userspace.nio.process.ProcessLineChannel
import borg.trikeshed.parse.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.*

/** A real Python subprocess with an owned test API; no model dependency or checkpoint downloads. */
class JevaProcessTest {
    @TempDir lateinit var directory: Path

    fun fixture(): JevaConfig {
        val source = File(directory.toFile(), "source").apply { mkdirs() }
        for (module in listOf("torch", "transformers", "peft", "accelerate")) File(source, "$module.py").writeText("__version__ = 'fixture-1'\n")
        File(source, "yaml.py").writeText("import json\nsafe_load = json.load\n")
        File(source, "jeva.py").writeText("""
            import os, json
            print('library import chatter')
            class Result:
                def __init__(self, kind):
                    self.kind = kind
                def to_dict(self):
                    return dict(kind=self.kind, candidate_probabilities=[0.1, 0.9], null_probability=0.0, noul=0.9,
                                usage=dict(input_tokens=2, output_tokens=0))
            class Jeva:
                loads = 0
                @classmethod
                def from_pretrained(cls, path, **kwargs):
                    assert kwargs['local_files_only'] is True
                    assert kwargs['trust_remote_code'] is False
                    assert kwargs['truncation'] == 'error'
                    assert os.environ['HF_HUB_OFFLINE'] == '1'
                    assert os.environ['GRAAL_PYTHON_VM_ARGS'] == '--experimental-options --python.BackgroundGCTaskMinimum=0 --python.BackgroundGCTaskThreshold=0'
                    assert os.environ['PYTORCH_MPS_HIGH_WATERMARK_RATIO'] == '0.7'
                    assert os.environ['PYTORCH_MPS_LOW_WATERMARK_RATIO'] == '0.6'
                    cls.loads += 1
                    assert cls.loads == 1
                    with open(os.path.join(os.path.dirname(__file__), 'loads.log'), 'a') as handle:
                        handle.write('load\n')
                    print('model load chatter')
                    return cls()
                def predict(self, **kwargs):
                    if kwargs['state'] == 'overflow':
                        raise ValueError('state token limit exceeded')
                    if kwargs['state'].startswith('sequence'):
                        import __main__
                        group = list(__main__.request['questions'])
                        assert len(group) == 1
                        with open(os.path.join(os.path.dirname(__file__), 'calls.jsonl'), 'a') as handle:
                            handle.write(json.dumps(dict(group=group, **kwargs)) + '\n')
                        if kwargs['question'] == 'fail':
                            raise ValueError('fixture prediction failure')
                        if kwargs['question'] == 'drift':
                            __main__.identity += '-changed'
                    print('prediction chatter')
                    return Result(kwargs['decision_type'])
        """.trimIndent())
        val model = File(directory.toFile(), "model").apply { mkdirs() }
        File(model, "config.yaml").writeText("""{"model":{"backbone":"backbone","tune_mode":"frozen"}}""")
        File(model, "head.pt").writeText("fixture-head")
        val backbone = File(model, "backbone").apply { mkdirs() }
        File(backbone, "model.safetensors").writeText("fixture-weights")
        File(backbone, "config.json").writeText("{}")
        File(backbone, "tokenizer_config.json").writeText("{}")
        File(backbone, "tokenizer.json").writeText("{}")
        return JevaConfig(runtime = JevaRuntime.PYTHON, model = model.path, source = source.path, timeoutMs = 10_000, env = mapOf(
            "GRAAL_PYTHON_VM_ARGS" to "--experimental-options --python.BackgroundGCTaskMinimum=0 --python.BackgroundGCTaskThreshold=0",
            "PYTORCH_MPS_HIGH_WATERMARK_RATIO" to "0.7", "PYTORCH_MPS_LOW_WATERMARK_RATIO" to "0.6",
            "HF_HUB_OFFLINE" to "0"))
    }

    @Test fun actualPythonBindingLoadsOnceRoutesThroughContextAndKeepsChatterOffProtocol() = runBlocking {
        val config = fixture()
        val element = JevaElement(CoroutineScope(coroutineContext + JvmProcessOperations()), config)
        try {
            val status = element.availability()
            assertEquals(true, status["available"], status["error"].toString())
            assertEquals(false, status["loaded"])
            assertTrue(status["model_identity"].toString().startsWith(config.model + "@"))
            val runtime = status["runtime"] as Map<*, *>
            assertEquals(borg.trikeshed.job.ContentId.of(JevaProcess.program.encodeToByteArray()).hex, runtime["worker_sha256"])
            assertEquals("fixture-1", (runtime["libraries"] as Map<*, *>)["torch"])
            val questions = mapOf("stated" to Jev.noul("Is the norm stated?"))
            repeat(2) {
                val reply = withContext(element) { DecisionProvider.JEVA.ask("", "page", questions) }
                assertEquals(0.9, Jev.noulOf(reply["answers"] as Map<*, *>, "stated"))
                assertEquals(status["model_identity"], reply["model"])
            }
            assertEquals(true, element.metadata["loaded"])
            File(config.source!!, "jeva.py").appendText("\n# change after model load\n")
            val loaded = element.exchange("""{"op":"status"}""")
            assertEquals(status["model_identity"], loaded["model_identity"])
            assertEquals(runtime, loaded["runtime"])
            val failure = assertFails { element.ask("overflow", questions) }
            assertContains(failure.message.orEmpty(), "state token limit exceeded")
        } finally { element.drain() }
        assertTrue(element.supervisor.isCompleted)
    }

    @Test fun runtimeIdentityTracksImportedSourceAndLibraryVersion(): Unit = runBlocking {
        val config = fixture()
        suspend fun identity(): String {
            val element = JevaElement(CoroutineScope(coroutineContext + JvmProcessOperations()), config)
            return try {
                val status = element.availability()
                assertEquals(true, status["available"], status["error"].toString())
                status["model_identity"].toString()
            } finally { element.drain() }
        }
        val original = identity()
        assertEquals(original, identity())
        File(config.source!!, "jeva.py").appendText("\n# changed fixture source\n")
        val changedSource = identity()
        assertNotEquals(original, changedSource)
        File(config.source, "torch.py").writeText("__version__ = 'fixture-version-2'\n")
        assertNotEquals(changedSource, identity())
    }

    @Test fun twentyQuestionsKeepOneLoadExactOrderAndStateAcrossIndividualExchanges(): Unit = runBlocking {
        val config = fixture()
        val element = JevaElement(CoroutineScope(coroutineContext + JvmProcessOperations()), config)
        val state = "sequence\nquoted \"state\" café"
        val questions = (0 until 20).associate { "q$it" to Jev.noul("question-$it", "yes-$it", "no-$it") }
        try {
            val reply = element.ask(state, questions)
            assertEquals(questions.keys.toList(), (reply["answers"] as Map<*, *>).keys.toList())
            assertEquals(mapOf("input_tokens" to 40L, "output_tokens" to 0L), reply["usage"])
            val calls = File(config.source!!, "calls.jsonl").readLines().map(::reifyMap)
            assertEquals(20, calls.size)
            calls.forEachIndexed { i, call ->
                assertEquals(listOf("q$i"), call["group"])
                assertEquals(state, call["state"])
                assertEquals("question-$i", call["question"])
                assertEquals(listOf("false: no-$i", "true: yes-$i"), call["options"])
            }
            assertEquals(listOf("load"), File(config.source, "loads.log").readLines())
        } finally { element.drain() }
    }

    @Test fun failedPredictionRejectsTheWholeAdmittedAnswerSet(): Unit = runBlocking {
        val config = fixture()
        val element = JevaElement(CoroutineScope(coroutineContext + JvmProcessOperations()), config)
        val questions = (0 until 20).associate { "q$it" to Jev.noul(if (it == 6) "fail" else "question-$it") }
        try {
            val failure = assertFails { element.ask("sequence", questions) }
            assertContains(failure.message.orEmpty(), "fixture prediction failure")
            val calls = File(config.source!!, "calls.jsonl").readLines().map(::reifyMap)
            assertEquals((0..6).map { listOf("q$it") }, calls.map { it["group"] })
            assertEquals(listOf("load"), File(config.source, "loads.log").readLines())
        } finally { element.drain() }
    }

    @Test fun changedModelIdentityRejectsEarlierAnswersAndStopsRemainingExchanges(): Unit = runBlocking {
        val config = fixture()
        val element = JevaElement(CoroutineScope(coroutineContext + JvmProcessOperations()), config)
        val questions = (0 until 20).associate { "q$it" to Jev.noul(if (it == 4) "drift" else "question-$it") }
        try {
            val failure = assertFails { element.ask("sequence", questions) }
            assertContains(failure.message.orEmpty(), "model identity changed")
            val calls = File(config.source!!, "calls.jsonl").readLines().map(::reifyMap)
            assertEquals((0..4).map { listOf("q$it") }, calls.map { it["group"] })
        } finally { element.drain() }
    }

    @Test fun absentOrInvalidWorkerFingerprintIsUnavailable(): Unit = runBlocking {
        for (fingerprint in listOf(null, "invalid", "A".repeat(64))) {
            val channel = ProcessLineChannel.open(CoroutineScope(coroutineContext + JvmProcessOperations()), "python3",
                listOf("-u", "-c", JevaProcess.program, jsonOf(mapOf("worker_sha256" to fingerprint))), timeoutMs = 10_000)
            try {
                val status = reifyMap(channel.exchange("""{"op":"status"}"""))
                assertEquals(false, status["available"])
                assertContains(status["error"].toString(), "worker_sha256")
            } finally { channel.drain() }
        }
    }

    @Test fun incompleteTokenizerIsUnavailableBeforeLoadingTheModel(): Unit = runBlocking {
        val config = fixture()
        File(config.model, "backbone/tokenizer.json").delete()
        val element = JevaElement(CoroutineScope(coroutineContext + JvmProcessOperations()), config)
        try {
            val status = element.availability()
            assertEquals(false, status["available"])
            assertContains(status["error"].toString(), "tokenizer files are missing")
            assertFails { element.ask("page", mapOf("stated" to Jev.noul("Stated?"))) }
        } finally { element.drain() }
    }

    @Test fun releaseDrainsPythonAndCatalogPollingDoesNotRestartIt(): Unit = runBlocking {
        val config = fixture()
        val element = JevaElement(CoroutineScope(coroutineContext + JvmProcessOperations()), config)
        val questions = mapOf("stated" to Jev.noul("Is the norm stated?"))
        try {
            val first = element.ask("page", questions)
            assertNotNull(element.process)
            val released = element.release()
            assertEquals(true, released["available"])
            assertEquals(false, released["loaded"])
            assertEquals(first["model"], released["model_identity"])
            assertNull(element.process)
            repeat(3) { assertEquals(released, element.availability()) }
            assertNull(element.process)
            assertEquals(listOf("load"), File(config.source!!, "loads.log").readLines())
            val reloaded = element.ask("page", questions)
            assertEquals(first["model"], reloaded["model"])
            assertEquals(first["answers"], reloaded["answers"])
            assertNotNull(element.process)
            assertEquals(listOf("load", "load"), File(config.source, "loads.log").readLines())
            assertTrue(element.accepting)
        } finally { element.drain() }
        assertNull(element.process)
        assertTrue(element.supervisor.isCompleted)
    }
}
