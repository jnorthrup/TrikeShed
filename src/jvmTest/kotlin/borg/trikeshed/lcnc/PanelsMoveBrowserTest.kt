package borg.trikeshed.lcnc

import borg.trikeshed.job.*
import borg.trikeshed.lcnc.ccek.*
import borg.trikeshed.lib.*
import borg.trikeshed.module.*
import borg.trikeshed.parse.*
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import borg.trikeshed.vm.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.*
import kotlin.test.*

/** The existing board gesture, module command and immutable receipt across page drain. */
class PanelsMoveBrowserTest {
    @Test
    @org.junit.jupiter.api.Timeout(value = 12, unit = java.util.concurrent.TimeUnit.MINUTES)
    fun aHeldCommittedMoveReceiptCannotRepaintAClosedBoard() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(SystemOperations.default.getenv("TRIKESHED_PANELS_MOVE_CLOSE_UI") == "1")
        data class Proof(val body: Map<*, *>, val held: Int, val released: Int, val posts: Int,
                         val at: Long = java.lang.System.nanoTime())
        val posts = CopyOnWriteArrayList<Map<*, *>>()
        val reports = ConcurrentHashMap<String, Proof>()
        val failures = CopyOnWriteArrayList<Map<*, *>>()
        val produced = AtomicBoolean(false)
        val held = AtomicInteger()
        val released = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val committed = AtomicReference<Map<*, *>?>()
        val moved = AtomicReference<Map<*, *>?>()
        lateinit var bound: ModuleContext
        fun fields(text: String) = assertIs<Map<*, *>>(reify(text))
        fun event(request: Map<*, *>): Map<*, *>? =
            ((request["pulse"] as? Map<*, *>)?.get("outputs") as? Map<*, *>)?.get("event") as? Map<*, *>
        fun proof(name: String) = assertNotNull(reports[name], "Missing native move observation: $name")
        fun snapshot(value: Proof) = assertIs<Map<*, *>>(value.body["snapshot"])
        val host = HypervisorVmHost()
        val binding = ccekReactorBinding(coroutineContext)
        try {
            binding.reactor.open()
            withContext(VmHostKey(host)) {
                val context = vmRuntimeTestContext(this)
                context.lcncRunners.putAll(PureNodes.registry { 1L })
                SubVmLegos.register(context)
                retainedSimulationBrowser(context, null, binding,
                    LcncProgramConfix.fromJson("preset-kanban", LcncPresets.all().getValue("preset-kanban")),
                    executorThreads = 8,
                    pageSuffix = moveControls(),
                    opened = { bound = it; assertNull(it.lcncRunners["prompt.chat"]) },
                    configure = { actual -> actual.lcncRunners.putAll(SurfaceNodes.registry { method, path, value ->
                        val raw = "$method $path HTTP/1.1\r\nContent-Type: application/json\r\n\r\n" + jsonOf(value)
                        reify(requireNotNull(actual.routes.match(path)?.route(method, path, raw, null)).body)
                    }) },
                    testRoute = { method, path, body ->
                        fun response() = borg.trikeshed.litebike.JvmKanbanServer.HttpResponse(200, "{}")
                        when {
                            method == "POST" && path == "/_test/move/release" -> { gate.complete(Unit); response() }
                            method == "POST" && path.startsWith("/_test/move/report/") -> {
                                check(reports.putIfAbsent(path.substringAfterLast('/'), Proof(fields(body), held.get(), released.get(), posts.size)) == null)
                                response()
                            }
                            method == "POST" && path == "/_test/failure" -> { failures += fields(body); response() }
                            method == "POST" && path == "/api/lcnc/run" -> {
                                val request = fields(body); posts += request
                                // Execute the production route once; only the real committed move response is held.
                                val actual = requireNotNull(bound.routes.match(path)?.route(method, path, body, null))
                                event(request)?.takeIf { it["kind"] == "move" }?.let { move ->
                                    check(produced.compareAndSet(false, true)) { "Only one actual move belongs to this scenario" }
                                    val run = fields(actual.body)
                                    check(actual.status == 200 && run["ok"] == true && run["status"] == "completed") { actual.body }
                                    val outputs = assertIs<Map<*, *>>(run["outputs"])
                                    val verdict = assertIs<List<*>>(assertIs<Map<*, *>>(outputs["n7"])["result"])
                                        .map { assertIs<Map<*, *>>(it) }.single { it["idempotencyKey"] == move["id"] }
                                    check(verdict["verdict"] == "committed") { actual.body }
                                    val board = fields(requireNotNull(bound.routes.match("/api/board")?.route("GET", "/api/board", "", null)).body)
                                    moved.set(assertIs<List<*>>(board["items"]).map { assertIs<Map<*, *>>(it) }.single { it["id"] == move["itemId"] })
                                    committed.set(run); held.incrementAndGet()
                                    try { gate.await() } finally { held.decrementAndGet(); released.incrementAndGet() }
                                }
                                actual
                            }
                            else -> null
                        }
                    },
                    observations = { mapOf("posts" to posts.toList(), "held" to held.get(), "released" to released.get(),
                        "committed" to committed.get(), "moved" to moved.get()) },
                    cleanup = { gate.complete(Unit) })
            }
            assertTrue(failures.isEmpty(), "Native control or fixture failure: $failures")
            assertEquals(3, posts.size, "One Run, one create, one move; no producer is replayed on close")
            assertTrue(posts.all { "document" in it && "type" !in it })
            val events = posts.mapNotNull(::event)
            assertEquals(listOf("create", "move"), events.map { it["kind"] })
            val move = events.last(); val item = assertIs<Map<*, *>>(move["item"])
            assertEquals("todo", move["from"]); assertEquals("ready", move["to"])
            assertEquals(item["id"], move["itemId"])
            assertEquals("ready", assertNotNull(moved.get())["status"])
            assertEquals((item["revision"] as Number).toLong() + 1, (assertNotNull(moved.get())["revision"] as Number).toLong())
            val run = assertNotNull(committed.get()); val cid = ContentId(assertIs<String>(run["receiptCid"]))
            val retained = assertNotNull(bound.casStore.get(cid)); assertEquals(cid, ContentId.of(retained))
            assertEquals(run["runId"], assertIs<Map<*, *>>(fields(retained.decodeToString())["lcncRun"])["runId"])
            val closing = proof("closing"); val pending = proof("pending"); val closed = proof("closed"); val quiet = proof("quiet")
            val drag = assertIs<Map<*, *>>(closing.body["drag"])
            assertEquals(true, drag["trusted"]); assertEquals(0.35, (drag["opacity"] as Number).toDouble())
            assertEquals(1.0, (drag["ghosts"] as Number).toDouble())
            for (value in arrayOf(closing, pending)) {
                assertEquals(true, value.body["closePending"]); assertEquals(1, value.held)
                assertEquals(0, value.released); assertEquals(3, value.posts)
            }
            assertTrue(pending.at - closing.at >= java.util.concurrent.TimeUnit.SECONDS.toNanos(1))
            assertEquals(true, closed.body["closed"]); assertEquals(0, closed.held); assertEquals(1, closed.released)
            for (value in arrayOf(closing, pending, closed, quiet)) {
                val dom = snapshot(value)
                assertEquals(setOf("boardHtml", "status", "cardConnected", "opacity", "ghosts", "dragovers"), dom.keys)
                assertIs<String>(dom["boardHtml"]); assertIs<String>(dom["status"])
                assertEquals(true, dom["cardConnected"]); assertEquals("", dom["opacity"])
                assertEquals(0.0, (dom["ghosts"] as Number).toDouble()); assertEquals(0.0, (dom["dragovers"] as Number).toDouble())
                assertEquals(3, value.posts)
            }
            assertContains(assertIs<String>(snapshot(closing)["status"]), "downstream deciding")
            // Intended RED follows the actual commit, dropped-pointer cleanup, held body and joined close.
            assertEquals(snapshot(closing)["status"], snapshot(closed)["status"], "A late committed move cannot rewrite the closed board status")
            assertEquals(snapshot(closing), snapshot(pending)); assertEquals(snapshot(closing), snapshot(closed))
            assertTrue(quiet.at - closed.at >= java.util.concurrent.TimeUnit.SECONDS.toNanos(1))
            assertEquals(snapshot(closed), snapshot(quiet))
            println("PANELS_MOVE_CLOSE_PROOF=" + jsonOf(mapOf("posts" to posts.toList(), "committed" to run,
                "moved" to moved.get(), "reports" to reports.mapValues { (_, v) -> mapOf("body" to v.body,
                    "held" to v.held, "released" to v.released, "posts" to v.posts) })))
        } finally { withContext(NonCancellable) { gate.complete(Unit); try { host.close() } finally { binding.reactor.drain() } } }
    }

    private fun moveControls(): String = """
        <aside id="moveCloseControls" style="position:fixed;right:8px;bottom:8px;z-index:100000;background:#151923;color:#fff;padding:10px;max-width:320px;border:2px solid #9ce">
          <strong>Board move close fixture</strong><div id="moveCloseButtons"></div><pre id="moveCloseState" style="white-space:pre-wrap"></pre>
        </aside><script>
        (function () {
          const state = window.PANELS_MOVE_CLOSE = {closed:false, closeStarted:false, drag:null, snapshots:{}};
          const output = document.getElementById('moveCloseState'); let completion = null, timer = 0;
          const snapshot = () => ({boardHtml:state.board?.outerHTML || '', status:state.status?.textContent || '',
            cardConnected:!!state.card?.isConnected, opacity:state.card?.style.opacity || '',
            ghosts:document.querySelectorAll('body > .kcard').length, dragovers:state.board?.querySelectorAll('.dragover').length || 0});
          const show = text => {output.textContent=text;};
          async function post(path, value) {
            const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(value || {})});
            if(!r.ok) throw new Error(path+': '+await r.text()); return r.json();
          }
          async function report(name, extra) {
            const value=Object.assign({snapshot:snapshot()},extra || {});state.snapshots[name]=value;
            await post('/_test/move/report/'+name,value);
          }
          async function fail(error) {state.failure=String(error.stack || error);show('Fixture error: '+state.failure);
            try {await post('/_test/failure',{detail:state.failure});} finally {await post('/_test/move/release');}}
          function button(label, action) {const b=document.createElement('button');b.textContent=label;b.style.cssText='display:block;margin:5px 0';
            b.addEventListener('click',()=>Promise.resolve().then(action).catch(fail));document.getElementById('moveCloseButtons').appendChild(b);}
          const dropped=event=>{if(!event.isTrusted || !state.card || state.closeStarted)return;
            const ghosts=document.querySelectorAll('body > .kcard').length;
            if(ghosts===1 && Number(state.card.style.opacity)===0.35)
              state.drag={trusted:event.isTrusted,ghosts:ghosts,opacity:Number(state.card.style.opacity)};};
          window.addEventListener('pointerup',dropped,true);
          button('Observe created card',async()=>{
            state.card=Array.from(document.querySelectorAll('.kboard .kcard')).find(c=>c.textContent.includes('Move close source'));
            if(!state.card || state.card.closest('.kcol')?.dataset.group!=='todo')throw new Error('Create Move close source in TODO first');
            state.board=state.card.closest('.kboard');state.status=state.board.parentElement.querySelector('.kboard-status');
            show('Native-drag that card more than 6px, then drop in this board Ready column. Then Close Panels.');
          });
          button('Close Panels',async()=>{
            const actual=await (await fetch('/_test/observations')).json();
            if(state.closeStarted || !state.drag || actual.held!==1 || actual.released!==0 || !actual.committed?.receiptCid)throw new Error('Wait for the real committed native move response');
            if(snapshot().ghosts!==0 || snapshot().opacity!=='' || snapshot().dragovers!==0)throw new Error('Drop must clear its actual pointer artifacts before close');
            state.closeStarted=true;completion=window.closePanels();completion.then(()=>{state.closed=true;},e=>{state.closeError=String(e);});
            await report('closing',{closePending:!state.closed,drag:state.drag});
            timer=setTimeout(()=>report('pending',{closePending:!state.closed}).then(()=>{state.pending=true;show('Held close recorded. Release committed move response.');}).catch(fail),1100);
          });
          button('Release committed move response',async()=>{
            if(!state.pending)throw new Error('Record pending drain before releasing');
            await post('/_test/move/release');await completion;await report('closed',{closed:state.closed});
            timer=setTimeout(()=>report('quiet').then(()=>{state.quiet=true;show('Quiet observation recorded. Finish browser check.');}).catch(fail),1100);
          });
          button('Finish browser check',async()=>{
            if(!state.quiet && !state.failure)throw new Error('Record the quiet observation first');
            clearTimeout(timer);window.removeEventListener('pointerup',dropped,true);await post('/_test/move/release');
            try {await (completion || window.closePanels());} finally {await post('/_test/finish');}show('Fixture finished; close the test tab.');
          });
          show('Use ordinary Run/Create item, Title=Move close source. Then Observe created card.');
        }());</script>
    """.trimIndent()
}
