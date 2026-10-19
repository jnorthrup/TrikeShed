package borg.trikeshed.lcnc

import borg.trikeshed.lcnc.ccek.*
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.JvmKanbanServer.HttpResponse
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

/** Source editing uses the published vocabulary and production Panels, with no guest invocation. */
class SourceEditorBrowserTest {
    @Test fun sourcePresentationComesFromTheCommonContract() {
        val publisher = LcncPublisher(borg.trikeshed.graal.ConfixBlackboard.empty(), { emptyMap() }, null)
        val contracts = (publisher.vocabularyPayload()["contracts"] as List<*>).map { it as Map<*, *> }
        for (type in listOf("vm.polyglot", "vm.camel", "vm.graalce", "vm.eval")) {
            val params = contracts.single { it["type"] == type }["params"] as Map<*, *>
            val source = params["source"] as Map<*, *>
            assertEquals(true, source["code"], "$type source offers the source editor")
            assertEquals(true, source["ta"])
        }
        val literal = contracts.single { it["type"] == "json.value" }["params"] as Map<*, *>
        assertNotEquals(true, (literal["value"] as Map<*, *>)["code"], "Data literals are not executable source")
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 12, unit = java.util.concurrent.TimeUnit.MINUTES)
    fun draftsApplyWithoutExecutionAndRefuseStaleOwners() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(SystemOperations.default.getenv("TRIKESHED_SOURCE_EDITOR_UI") == "1")
        val posts = CopyOnWriteArrayList<String>()
        val inspections = CopyOnWriteArrayList<String>()
        val proofs = CopyOnWriteArrayList<Map<*, *>>()
        val program = LcncProgram("source-editor", s_[
            LcncNode("function", "vm.polyglot", mapOf("language" to "js", "source" to "return input", "trust" to "OWN"),
                x = 0.0, y = 0.0),
            LcncNode("ring", "scope", children = s_[
                LcncNode("nested", "vm.polyglot", mapOf("language" to "python", "source" to "return input")),
            ], x = 420.0, y = 0.0),
        ], emptySeriesOf(), view = LcncView(x = 30.0, y = 30.0, zoom = 0.8))
        val binding = ccekReactorBinding(coroutineContext)
        try {
            binding.reactor.open()
            val context = vmRuntimeTestContext(this)
            context.lcncRunners["vm.polyglot"] = LcncNodeRunner { _, _ -> error("Editing must not invoke a guest") }
            retainedSimulationBrowser(context, null, binding, program,
                pageSuffix = """
                    <script>
                    // Fixture-only controls simulate a concurrent edit/load and an owner shutdown.
                    // All ordinary editing still uses the production dialog and inline controls.
                    const sourceDocument = document.createElement('script');
                    sourceDocument.type = 'application/json'; sourceDocument.id = 'source-test-document';
                    document.body.append(sourceDocument);
                    const captureSourceDocument = () => { sourceDocument.textContent = JSON.stringify(window.serialize()); };
                    let sourceRevision = 0;
                    const observeSourceDocument = () => {
                      sourceDocument.dataset.revision = String(++sourceRevision); captureSourceDocument();
                    };
                    window.addEventListener('lcnc:shadow-update', observeSourceDocument);
                    const sourceObserver = new MutationObserver(() => {
                      const dialog = document.querySelector('dialog[aria-label="Source editor"]');
                      if (!dialog || dialog.querySelector('[data-source-fixture]')) return;
                      const controls = document.createElement('div');
                      controls.dataset.sourceFixture = 'true';
                      controls.style.cssText = 'font:11px monospace;border-top:1px dashed #667;margin-top:8px';
                      controls.append('Isolated test controls: ');
                      const button = (name, action) => {
                        const b = document.createElement('button'); b.textContent = name;
                        b.onclick = action; controls.append(b);
                      };
                      button('Test concurrent source edit', () => {
                        window.G.nodes.find(n => n.id === 'function').params.source = 'return input + 10';
                        window.save();
                      });
                      button('Test undo while drafting', () => document.querySelector('#undoBtn').click());
                      button('Test page close', () => {
                        captureSourceDocument(); sourceObserver.disconnect();
                        window.removeEventListener('lcnc:shadow-update', observeSourceDocument);
                        window.closePanels();
                      });
                      dialog.firstElementChild.append(controls);
                    });
                    sourceObserver.observe(document.documentElement, {childList:true,subtree:true});
                    </script>
                """.trimIndent(),
                requests = { method, path ->
                    if (method == "POST" && path.startsWith("/api/")) {
                        // SpaceGraph's program alignment is read-only projection, not execution.
                        if (path == "/api/lcnc/rdf/align") inspections += path else posts += path
                    }
                },
                testRoute = { method, path, body ->
                    if (method == "POST" && path == "/_test/proof") {
                        proofs += reifyMap(body)
                        HttpResponse(200, "{}")
                    } else null
                }, observations = { mapOf("posts" to posts.toList(), "inspections" to inspections.toList(), "proofs" to proofs.toList()) })
            assertEquals(emptyList(), posts, "Editing, cancel, undo, redo and close perform no API execution")
            val proof = proofs.single()
            val checks = proof["checks"] as Map<*, *>
            for (check in listOf("draft", "cancel", "apply", "undo", "redo", "conflict", "replacement", "nested", "close"))
                assertEquals(true, checks[check], check)
            val document = proof["document"] as Map<*, *>
            val nodes = (document["nodes"] as List<*>).map { it as Map<*, *> }
            assertEquals("return input + 1", (nodes.single { it["id"] == "function" }["params"] as Map<*, *>)["source"])
            val nested = ((nodes.single { it["id"] == "ring" }["children"] as List<*>).single() as Map<*, *>)["params"] as Map<*, *>
            assertEquals("return input + 2", nested["source"])
            assertEquals("python", nested["language"])
        } finally { withContext(NonCancellable) { binding.reactor.drain() } }
    }
}
