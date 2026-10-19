package borg.trikeshed.lcnc

import borg.trikeshed.forge.server.*
import borg.trikeshed.lcnc.ccek.*
import borg.trikeshed.lib.*
import borg.trikeshed.litebike.JvmKanbanServer.HttpResponse
import borg.trikeshed.memory.*
import borg.trikeshed.modelmux.*
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Native guide and connection gestures against the production Panels and current common contracts. */
class PanelsSpritesBrowserTest {
    @Test
    @org.junit.jupiter.api.Timeout(value = 12, unit = java.util.concurrent.TimeUnit.MINUTES)
    fun inspectionStaysReadOnlyAndExplicitConnectionsRevalidateBeforePageDrain() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(SystemOperations.default.getenv("TRIKESHED_SPRITES_UI") == "1")
        val reports = ConcurrentHashMap<String, Map<*, *>>()
        val failures = CopyOnWriteArrayList<Map<*, *>>()
        val mutations = CopyOnWriteArrayList<String>()
        val catalogReads = AtomicInteger()
        val networkReads = AtomicInteger()
        val program = LcncProgram("sprite-guide", s_[
            LcncNode("source", "text.value", mapOf("value" to "visible-prefix-" + "x".repeat(6000) + "-truncated-tail"),
                x = 20.0, y = 20.0, collapsed = true),
            LcncNode("target", "display", x = 390.0, y = 20.0),
            LcncNode("anchor", "text.value", mapOf("value" to "Existing input"), x = 20.0, y = 190.0),
            LcncNode("content", "json.value", mapOf("value" to jsonOf(mapOf(
                "title" to "record", "password" to "fixture-secret", "details" to "x".repeat(6000) + "-hidden-tail"))),
                x = 760.0, y = 20.0, collapsed = true),
            LcncNode("counsel", "mux.chat", mapOf("system" to "Compare the evidence", "maxTokens" to "73", "temperature" to "0.4"),
                x = 760.0, y = 190.0, collapsed = true),
            LcncNode("stale", "text.value", mapOf("value" to "Concurrent edit"), x = 20.0, y = 420.0),
            LcncNode("staleTarget", "display", x = 390.0, y = 420.0),
            LcncNode("blocker", "display", x = 760.0, y = 420.0),
            LcncNode("binding", "text.value", mapOf("value" to "/fixture/workspace"), x = 20.0, y = 850.0, collapsed = true),
            LcncNode("typed", "coalesce", x = 20.0, y = 680.0),
            LcncNode("mount", "project.mount", x = 390.0, y = 680.0),
        ], s_[LcncWire("anchor", "value", "target", "x"), LcncWire("binding", "value", "typed", "a?")],
            view = LcncView(20.0, 20.0, 0.7))
        val binding = ccekReactorBinding(coroutineContext)
        try {
            binding.reactor.open()
            val context = vmRuntimeTestContext(this)
            context.lcncRunners.putAll(PureNodes.registry { 1L })
            val memory = MemoryIndexLayer(MemoryStore(context.casStore, context.couch.store))
            val scopes = ProjectScopes(JvmFileOperations(), context.attachments,
                CouchIndexBridge(context.attachments, memory), context.casStore, null)
            val patch = PatchWire(BrainClient(apiKey = "fixture", base = "https://fixture.invalid/v1", model = "fixture"),
                scopes, attachments = context.attachments)
            retainedSimulationBrowser(context, null, binding, program, pageSuffix = controls(),
                requests = { method, path ->
                    if (method !in setOf("GET", "HEAD") && path.startsWith("/api/") && path != "/api/lcnc/rdf/align")
                        mutations += "$method $path"
                },
                testRoute = { method, path, body ->
                    when {
                        method == "GET" && path in setOf("/api/panels/presets", "/api/panels") ->
                            patch.route(method, path, "", null)
                        method == "GET" && path == "/api/lcnc/network/status" -> {
                            networkReads.incrementAndGet()
                            HttpResponse(200, jsonOf(mapOf("state" to "ACTIVE", "bootstrapPeers" to 2,
                                "registeredPrograms" to 1, "name" to "fixture-network-owner")))
                        }
                        method == "GET" && path == "/api/mux/keys" -> HttpResponse(200, jsonOf(mapOf("roster" to listOf(
                            mapOf("name" to "provider-a", "keyPresent" to false, "envVar" to "FIXTURE_A_KEY", "base" to "https://a.invalid/v1"),
                            mapOf("name" to "provider-b", "keyPresent" to true, "envVar" to "FIXTURE_B_KEY", "base" to "https://b.invalid/v1"),
                        ))))
                        method == "GET" && path == "/api/mux/models" -> {
                            catalogReads.incrementAndGet()
                            HttpResponse(200, jsonOf(mapOf("models" to listOf(
                                mapOf("name" to "provider-a", "model" to "shared-model"),
                                mapOf("name" to "provider-b", "model" to "shared-model"),
                            ))))
                        }
                        method == "POST" && path.startsWith("/_test/sprite/report/") -> {
                            check(reports.putIfAbsent(path.substringAfterLast('/'), reifyMap(body)) == null)
                            HttpResponse(200, "{}")
                        }
                        method == "POST" && path == "/_test/failure" -> {
                            failures += reifyMap(body); HttpResponse(200, "{}")
                        }
                        else -> null
                    }
                }, observations = { mapOf("reports" to reports.toMap(), "mutations" to mutations.toList(), "failures" to failures.toList()) })
            assertTrue(failures.isEmpty(), "Native browser fixture failed: $failures")
            assertTrue(mutations.isEmpty(), "Guidance and authoring cannot execute, save remotely or request a model: $mutations")
            fun report(name: String) = assertNotNull(reports[name], "Missing browser observation: $name")
            fun wires(value: Map<*, *>) = assertIs<List<*>>(value["wires"]).map { assertIs<Map<*, *>>(it) }
            fun connected(value: Map<*, *>, from: String, to: String) = wires(value).any {
                it["from"] == listOf(from, "value") && it["to"] == listOf(to, "x")
            }
            val inspected = report("inspection")
            assertEquals("source", inspected["guide"])
            assertEquals("dialog", inspected["role"])
            assertEquals("LCNC node guide", inspected["label"])
            assertEquals(2, wires(inspected).size)
            val text = assertIs<String>(inspected["content"])
            assertContains(text, "visible-prefix-")
            assertFalse("truncated-tail" in text)
            assertTrue(text.length < 500)
            assertTrue(assertIs<List<*>>(inspected["candidates"]).any {
                val candidate = assertIs<Map<*, *>>(it)
                candidate["from"] == "source" && candidate["to"] == "target"
            })
            val applied = report("connected")
            assertEquals(3, wires(applied).size)
            assertTrue(connected(applied, "anchor", "target"), "Adding a MANY input preserves its existing producer")
            assertTrue(connected(applied, "source", "target"))
            val content = report("content")
            assertEquals("content", content["guide"])
            val preview = assertIs<String>(content["content"])
            assertContains(preview, "record")
            assertContains(preview, "[redacted]")
            assertFalse("fixture-secret" in preview)
            assertFalse("hidden-tail" in preview)
            assertTrue(preview.length < 1500)
            val counsel = report("counsel")
            assertEquals("counsel", counsel["guide"])
            assertTrue(assertIs<List<*>>(counsel["candidates"]).any {
                val candidate = assertIs<Map<*, *>>(it)
                candidate["from"] == "stale" && candidate["to"] == "counsel" && candidate["toPort"] == "prompt?"
            }, "Explicit effect-node connections remain available without invoking the model")
            assertEquals(wires(applied), wires(counsel))
            val modelsInspection = report("models-inspection")
            assertContains(assertIs<String>(modelsInspection["modelDialog"]), "FIXTURE_B_KEY")
            assertTrue(assertIs<String>(modelsInspection["models"]).isBlank(), "Inspecting and drafting routes cannot change a seat")
            val modelsApplied = report("models-applied")
            assertEquals("", modelsApplied["modelDialog"])
            val options = BrainChatOptions.parse(assertIs<Map<*, *>>(modelsApplied["seat"]))
            assertEquals(73, options.maxTokens)
            assertEquals(0.4, options.temperature)
            assertEquals(1, options.models.size)
            assertEquals("provider-b", options.models[0].a)
            assertEquals("shared-model", options.models[0].b)
            assertTrue(catalogReads.get() >= 2, "Apply must re-read discovery before authoring the route")
            assertEquals(wires(applied), wires(modelsApplied))
            val derived = report("derived")
            assertEquals(4, wires(derived).size)
            assertTrue(wires(derived).any { it["from"] == listOf("typed", "value") && it["to"] == listOf("mount", "path?") },
                "A graph-resolved text binding commits through the same shared operation as the offered choice")
            val stale = report("stale")
            assertEquals(5, wires(stale).size)
            assertTrue(connected(stale, "stale", "blocker"))
            assertFalse(connected(stale, "stale", "staleTarget"))
            assertContains(assertIs<String>(stale["status"]), "Connection not added")
            val entry = report("entry")
            assertEquals("", entry["guide"], "Opening another construction closes the stale node guide")
            assertContains(assertIs<List<*>>(entry["entries"]), "preset-scope")
            val loaded = LcncProgramConfix.fromJson("entry", jsonOf(entry["document"]))
            val ring = loaded.nodes.view.single { it.id == "r1" }
            val nested = ring.children.view.single { it.id == "r2" }
            assertEquals(2, nested.children.size)
            assertEquals(8, loaded.wires.size)
            val network = report("network")
            assertContains(assertIs<String>(network["networkDialog"]), "State ACTIVE")
            assertEquals(1, networkReads.get())
            val closed = report("closed")
            assertEquals(true, closed["closed"])
            assertEquals("", closed["guide"])
            assertEquals(0, (closed["highlights"] as Number).toInt())
            assertEquals(wires(entry), wires(closed))
            val clicks = assertIs<List<*>>(closed["clicks"]).map { assertIs<Map<*, *>>(it) }
            assertTrue(clicks.any { it["node"] == "source" && it["trusted"] == true })
            assertTrue(clicks.any { it["from"] == "source" && it["to"] == "target" && it["trusted"] == true })
            assertTrue(clicks.any { it["from"] == "stale" && it["to"] == "staleTarget" && it["trusted"] == true })
            assertTrue(clicks.any { it["modelAction"] == "Apply routes to seat" && it["trusted"] == true })
            assertTrue(clicks.any { it["from"] == "typed" && it["to"] == "mount" && it["trusted"] == true })
            assertTrue(clicks.any { it["entry"] == "preset-scope" && it["trusted"] == true })
            println("PANELS_SPRITES_PROOF=" + jsonOf(mapOf("reports" to reports.toMap(), "mutations" to mutations.toList())))
        } finally { withContext(NonCancellable) { binding.reactor.drain() } }
    }

    fun controls(): String = """
        <aside id="spriteFixture" style="position:fixed;left:8px;bottom:8px;z-index:100000;background:#151923;color:#fff;padding:10px;max-width:340px;border:2px solid #9ce;font:12px sans-serif">
          <strong>Node guide fixture</strong>
          <ol><li>Inspect source; capture inspection.</li><li>Connect source.value to target.x; capture connection.</li>
          <li>Inspect content; capture content.</li><li>Inspect counsel; capture counsel. Choose provider &amp; model, select provider-b and Add route; capture model inspection. Apply; capture model selection.</li>
          <li>Inspect typed, connect its value to mount.path?; capture derived connection.</li>
          <li>Inspect stale, occupy its source, then click its old staleTarget choice; capture refusal.</li>
          <li>Palette → Rings inside rings → Open construction; capture entry. Open Concentric work network; capture network; close network.</li>
          <li>Open a guide in the new construction, then Close page and finish.</li></ol>
          <div id="spriteFixtureButtons"></div><output id="spriteFixtureState"></output>
        </aside><script>
        (() => {
          const clicks=[]; let closed=false;
          document.addEventListener('click', event => {
            const node=event.target.closest?.('button[data-sprite]');
            const choice=event.target.closest?.('button[data-sprite-from]');
            const modelAction=event.target.closest?.('#panelModelSelection button');
            const entry=event.target.closest?.('button[data-entry-load]');
            if(node) clicks.push({node:node.dataset.sprite,trusted:event.isTrusted});
            if(choice) clicks.push({from:choice.dataset.spriteFrom,to:choice.dataset.spriteTo,trusted:event.isTrusted});
            if(modelAction) clicks.push({modelAction:modelAction.textContent,trusted:event.isTrusted});
            if(entry) clicks.push({entry:entry.dataset.entryLoad,trusted:event.isTrusted});
          },true);
          const state=document.getElementById('spriteFixtureState');
          const snapshot=() => {
            const guide=document.querySelector('section[data-sprite-panel]');
            const seat=window.G.nodes.find(node => node.id==='counsel')?.params || {};
            return {guide:guide?.dataset.spritePanel || '',role:guide?.getAttribute('role') || '',
              label:guide?.getAttribute('aria-label') || '',content:guide?.querySelector('[data-sprite-content]')?.textContent || '',
              status:guide?.querySelector('[data-sprite-status]')?.textContent || '',closed,clicks:[...clicks],
              modelDialog:document.getElementById('panelModelSelection')?.textContent || '',models:seat.models || '',seat:{...seat},
              networkDialog:document.getElementById('panelWorkNetwork')?.textContent || '',
              entries:Array.from(document.querySelectorAll('details[data-entry-program]')).map(entry => entry.dataset.entryProgram),
              highlights:document.querySelectorAll('.sprite-selected,.sprite-mate,.sprite-port,.sprite-arrow').length,
              wires:JSON.parse(JSON.stringify(window.serialize().wires)),
              candidates:Array.from(guide?.querySelectorAll('button[data-sprite-from]') || []).map(button =>
                ({from:button.dataset.spriteFrom,fromPort:button.dataset.spriteFromPort,to:button.dataset.spriteTo,toPort:button.dataset.spriteToPort}))};
          };
          async function post(path,value) {
            const response=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(value)});
            if(!response.ok) throw new Error(await response.text());
          }
          async function report(name) {
            const value=snapshot();if(name==='entry') value.document=JSON.parse(JSON.stringify(window.serialize()));
            await post('/_test/sprite/report/'+name,value);state.textContent='Captured '+name;
          }
          function button(label,action) {
            const button=document.createElement('button');button.textContent=label;
            button.onclick=async () => {try {await action();} catch(error) {
              state.textContent=String(error);await post('/_test/failure',{error:String(error)});
            }};document.getElementById('spriteFixtureButtons').append(button);
          }
          button('Capture inspection',() => report('inspection'));
          button('Capture connection',() => report('connected'));
          button('Capture content',() => report('content'));
          button('Capture counsel',() => report('counsel'));
          button('Capture model inspection',() => report('models-inspection'));
          button('Capture model selection',() => report('models-applied'));
          button('Capture derived connection',() => report('derived'));
          button('Occupy stale source',() => {
            if(window.G.wires.some(wire => wire.from[0]==='stale')) throw new Error('Stale source already occupied');
            window.G.wires.push({from:['stale','value'],to:['blocker','x']});window.save();
            state.textContent='Concurrent wire added; choose the old staleTarget connection.';
          });
          button('Capture stale refusal',() => report('stale'));
          button('Capture entry',() => report('entry'));
          button('Capture network',() => report('network'));
          button('Close page and finish',async () => {
            if(!document.querySelector('section[data-sprite-panel]')) throw new Error('Open a guide before close');
            await window.closePanels();closed=true;await report('closed');await fetch('/_test/finish');
          });
        })();
        </script>
    """.trimIndent()
}
