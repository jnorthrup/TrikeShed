package borg.trikeshed.web.spacegraph

import borg.trikeshed.web.*
import borg.trikeshed.web.patch.PatchHtml
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * RUNNERS: execution bodies ONLY. The node VOCABULARY — titles, ports, kinds, cardinality, param
 * defaults, source/sink/wide flags — is authored ONCE in Kotlin (LcncContracts) and hydrated at boot
 * from /api/lcnc/contracts. This file carries NO type table. A runner without a server contract is
 * dead code.
 */
class Runner(val run: suspend (n: dynamic, i: dynamic) -> dynamic, var render: ((n: dynamic, out: dynamic) -> Unit)? = null)

private fun o(vararg pairs: Pair<String, dynamic>): dynamic { val r = obj(); for ((k, v) in pairs) r[k] = v; return r }
private fun refuse(v: dynamic): Nothing = throw PatchRefusal(v)

internal fun runners(p: Panels): MutableMap<String, Runner> {
    fun pp(n: dynamic, k: String): dynamic = n.params[k]
    val enc = ::encodeURIComponent
    fun defined(v: dynamic) = v !== undefined
    return mutableMapOf(
        "vm.spawn" to Runner({ n, _ ->
            val b = o("id" to pp(n, "id"), "facet" to pp(n, "facet"), "wallMillis" to num(pp(n, "wallMillis")))
            val w = str(pp(n, "world")).split(",").map { it.trim() }.filter { it.isNotEmpty() }; if (w.isNotEmpty()) b.world = w.toTypedArray()
            val r = api("POST", "/api/vm/spawn", b)
            if (truthy(r.error) && !str(r.error).contains("exists")) refuse(r.error)
            o("vmId" to (if (truthy(r.id)) r.id else b.id))
        }),
        "vm.eval" to Runner({ n, i ->
            val src = if (defined(i["source?"])) str(i["source?"]) else pp(n, "source")
            val r = api("POST", "/api/vm/" + enc(str(i.vmId)) + "/eval", o("source" to src, "name" to "panel"))
            if (truthy(r.error)) refuse(r.error)
            o("value" to r.value, "cid" to r.cid)
        }),
        "vm.revoke" to Runner({ _, i -> val r = api("POST", "/api/vm/" + enc(str(i.vmId)) + "/revoke", obj()); o("ok" to !truthy(r.error)) }),
        "vms.list" to Runner({ _, _ -> val r = api("GET", "/api/vm"); o("rows" to (if (truthy(r.rows)) r.rows else r)) }),
        "pytest.pure" to Runner({ n, i ->
            val flags = str(pp(n, "flags")).split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(",") { JSON.stringify(it) }
            val src = """import sys, io, __future__, importlib.machinery
sys.path[:0]=['/workspace/puresite','/workspace/computronium']
class FutureLoader(importlib.machinery.SourceFileLoader):
    def source_to_code(self, data, path, *, _optimize=-1):
        flags = __future__.annotations.compiler_flag if str(path).startswith('/workspace/computronium') else 0
        return compile(data, path, 'exec', flags=flags, dont_inherit=True, optimize=_optimize)
sys.path_hooks.insert(0, importlib.machinery.FileFinder.path_hook((FutureLoader, importlib.machinery.SOURCE_SUFFIXES)))
sys.path_importer_cache.clear()
import typing, typing_extensions as te
for _n in dir(te):
    if not _n.startswith('_') and not hasattr(typing, _n):
        try: setattr(typing, _n, getattr(te, _n))
        except Exception: pass
import pytest
buf = io.StringIO(); old=(sys.stdout,sys.stderr); sys.stdout=sys.stderr=buf
try:
    rc = pytest.main([""" + flags + ", " + JSON.stringify(pp(n, "path")) + """])
finally:
    sys.stdout,sys.stderr = old
(rc, buf.getvalue()[-2000:])"""
            val r = api("POST", "/api/vm/" + enc(str(i.vmId)) + "/eval", o("source" to src, "name" to "panel-pytest"))
            if (truthy(r.error)) refuse(r.error)
            o("exit" to (if (truthy(r.value)) r.value[0] else r.value), "tail" to (if (truthy(r.value)) r.value[1] else r.value))
        }),
        "graal.vitals" to Runner({ _, _ -> o("json" to api("GET", "/api/graal/vitals")) }),
        "blackboard.facts" to Runner({ _, _ -> o("facts" to api("GET", "/blackboard/facts")) }),
        "blackboard.board" to Runner({ _, _ -> o("board" to api("GET", "/blackboard/board")) }),
        "blackboard.sites" to Runner({ _, _ -> o("sites" to api("GET", "/blackboard/sites")) }),
        "http.get" to Runner({ n, _ -> o("json" to api("GET", str(pp(n, "path")))) }),
        "http.post" to Runner({ n, i -> o("json" to api("POST", str(pp(n, "path")), i.body)) }),
        // board.get / board.view: no browser runner — serverRun() sends them to the daemon's unit.
        "timer" to Runner({ _, _ -> o("tick" to js("Date.now()")) }),
        "js" to Runner({ n, i -> val f = js("Function")("x", "return (" + str(pp(n, "expr")) + ")"); o("y" to f(i.x)) }),
        "pick" to Runner({ n, i ->
            var v: dynamic = i.x
            for (k in str(pp(n, "path")).split(".").filter { it.isNotEmpty() }) v = if (v == null) undefined else v[k]
            o("y" to v)
        }),
        "display" to Runner({ n, i -> p.setResult(n, i.x); obj() }),
        "gauge" to Runner({ n, i ->
            var v: dynamic = i.x
            if (typeOf(v) == "number" && num(v) > 9999) v = (js("(v/1e6).toFixed(1)") as String) + "M"
            n.el.querySelector(".gaugeval").textContent = str(v)
            n.el.querySelector(".gaugelabel").textContent = pp(n, "label"); obj()
        }),
        "mux.chat" to Runner({ n, i ->
            val body = o("prompt" to (if (defined(i["prompt?"])) str(i["prompt?"]) else pp(n, "prompt")),
                "maxTokens" to num(pp(n, "maxTokens")), "temperature" to num(pp(n, "temperature")))
            if (truthy(pp(n, "system"))) body.system = pp(n, "system")
            // the seat's admissible key×model pairs ride the call
            val mlist: dynamic = try { JSON.parse(if (truthy(pp(n, "models"))) str(pp(n, "models")) else "[]") } catch (e: dynamic) { jsArray() }
            if (isArray(mlist) && num(mlist.length) > 0) body.models = mlist
            val r = api("POST", "/api/mux/chat", body)
            if (r.verdict != "ok") refuse(r.detail ?: r.verdict ?: "mux error")
            o("content" to r.content, "model" to r.model)
        }),
        // mux.models / keys.status have NO client twin: the server runners are the contract.
        "project.kill" to Runner({ n, _ ->
            val name = pp(n, "name"); if (!truthy(name)) refuse("name required")
            val r = api("DELETE", "/api/projects/" + encodeURIComponent(str(name)))
            o("verdict" to r)
        }),
        "project.mount" to Runner({ n, i ->
            val r = api("POST", "/api/projects", o("path" to (if (defined(i["path?"])) str(i["path?"]) else pp(n, "path"))))
            if (r.verdict != "ok") refuse(r.detail ?: "mount refused")
            o("scope" to r)
        }),
        "project.list" to Runner({ _, _ -> val r = api("GET", "/api/projects"); o("scopes" to (if (truthy(r.scopes)) r.scopes else r)) }),
        "kg.ingest" to Runner({ n, i ->
            val body = if (defined(i["text?"])) str(i["text?"]) else pp(n, "kg")
            val resp = fetchJs("/api/beliefs/kg", jsonInit("POST", body, "text/turtle"))
            val r = JSON.parse<dynamic>(awaitJs(resp.text()))
            if (truthy(r.error)) refuse(r.error)
            o("report" to r)
        }),
        "beliefs.review" to Runner({ n, i ->
            val r = api("POST", "/api/beliefs/review", o("facts" to i.facts, "turnSucceeded" to (pp(n, "turnSucceeded") == "true")))
            if (truthy(r.error)) refuse(r.error)
            o("landed" to r)
        }),
        "beliefs.resonate" to Runner({ n, i ->
            val body = o("goal" to (if (defined(i["goal?"])) str(i["goal?"]) else pp(n, "goal")), "k" to num(pp(n, "k")))
            if (truthy(pp(n, "taxonomy"))) body.taxonomy = pp(n, "taxonomy")
            if (pp(n, "mode") == "whitened") body.mode = "whitened"
            val r = api("POST", "/api/beliefs/resonate", body)
            if (truthy(r.error)) refuse(r.error)
            o("synonyms" to r.synonymPeaks, "antonyms" to r.antonymPeaks)
        }),
        "beliefs.introspect" to Runner({ _, _ -> o("field" to api("GET", "/api/beliefs/introspect")) }),
        "pointcut.routes" to Runner({ _, _ -> val r = api("GET", "/api/graal/pointcuts"); o("routes" to (if (truthy(r.routes)) r.routes else r)) }),
        "list.pairs" to Runner({ n, _ ->
            val a: dynamic = try { JSON.parse(if (truthy(pp(n, "pairs"))) str(pp(n, "pairs")) else "[]") } catch (e: dynamic) { jsArray() }
            o("pairs" to (if (isArray(a)) a else jsArray()))
        }),
        "list.groupBy" to Runner({ n, i ->
            val key = str(pp(n, "key")); val out = obj()
            for (item in arr(if (truthy(i.x)) i.x else jsArray())) {
                var v: dynamic = item
                for (k in key.split(".").filter { it.isNotEmpty() }) v = if (v == null) undefined else v[k]
                val g = str(v); if (!truthy(out[g])) out[g] = jsArray(); out[g].push(item)
            }
            o("groups" to out)
        }),
        "dom.board" to Runner({ n, i ->
            n._groups = if (truthy(i.groups)) i.groups else obj(); n._cols = if (truthy(i["columns?"])) i["columns?"] else null
            // a drag gesture fires downstream exactly once; no gesture emits NOTHING
            val mv = n._lastMove; n._lastMove = null
            if (truthy(mv)) o("move" to mv) else obj()
        }, { n, _ -> p.renderGroupedBoard(n) }),
        "panels.list" to Runner({ _, _ -> val r = api("GET", "/api/panels"); o("panels" to (if (truthy(r.panels)) r.panels else jsArray())) },
            { n, out -> p.renderPanelsList(n, if (truthy(out) && truthy(out.panels)) out.panels else jsArray()) }),
        "program.ref" to Runner({ _, _ -> obj() }, { n, _ -> p.renderProgramRef(n) }),
        // at canvas top level a formal parameter has no caller: its declared default IS the value
        "scope.in" to Runner({ n, _ -> o("value" to pp(n, "default")) }),
        "scope.out" to Runner({ n, i -> p.setResult(n, i.value); obj() }),
        // The ring executes IN the daemon — withContext(LcncScopeFrame) nesting. The browser posts the
        // ring (a named stored program, or its children as an inline document), paints the returned warm
        // base onto the bands, and emits the gathered returns.
        "scope" to Runner({ n, i -> p.runScope(n, i) }),
        "note" to Runner({ _, _ -> obj() }),
        "text.value" to Runner({ n, _ -> o("value" to pp(n, "value")) }),
        "json.value" to Runner({ n, _ ->
            val raw = str(if (truthy(pp(n, "value"))) pp(n, "value") else "").trim()
            if (raw.isEmpty()) o("value" to jsArray())
            else try { o("value" to JSON.parse(raw)) } catch (e: dynamic) { o("error" to "json.value: not valid json") }
        }),
        // press is an EDGE: it exists on the run its click fired, then it's gone
        "button" to Runner({ n, _ -> val t = n._pressedAt; n._pressedAt = null; if (truthy(t)) o("press" to t) else obj() }, { n, _ -> p.renderButtonNode(n) }),
        "slider" to Runner({ n, _ -> o("value" to num(n._sval ?: pp(n, "value") ?: 0)) }, { n, _ -> p.renderSliderNode(n) }),
        // every control is a PATCH POINT: url on a text wire, play/stop/rewind as trigger signals,
        // `ended` firing downstream when playback finishes
        "media.player" to Runner({ n, i ->
            val url = str(i.url)
            val m = p.ensureMedia(n, url)
            if (m.getAttribute("data-src") != url) { m.setAttribute("data-src", url); m.src = url }
            if (defined(i["volume?"])) { val vol = num(i["volume?"]); m.volume = maxOf(0.0, minOf(1.0, if (vol.isNaN() || vol == 0.0) 0.0 else vol)) }
            if (defined(i["rewind?"])) m.currentTime = 0
            if (defined(i["stop?"])) m.pause()
            if (defined(i["play?"])) { try { awaitJs(m.play()) } catch (e: CancellationException) { throw e } catch (e: dynamic) { p.setResult(n, "autoplay blocked — press the element's ▶ once") } }
            val fired = n._endedAt; n._endedAt = null
            val duration = num(m.duration)
            val out = o("state" to o("url" to url, "playing" to !truthy(m.paused), "t" to js("Math.round(m.currentTime*10)/10"),
                "duration" to (if (duration.isFinite()) js("Math.round(duration*10)/10") else 0)))
            if (truthy(fired)) out.ended = fired
            out
        }),
        "graal.events" to Runner({ n, _ -> o("event" to (if (truthy(n._lastEvent)) n._lastEvent else null)) }),
        "vm.events" to Runner({ n, _ -> o("event" to (if (truthy(n._lastEvent)) n._lastEvent else null)) }),
        // The concentric treesheet view: board sheet at the center, partitions one ring out,
        // orchestration outermost. SheetRef cells and child rings drill in, the crumb climbs out.
        "sheet.concentric" to Runner({ n, i ->
            val idx = obj()
            fun add(sh: dynamic) { if (truthy(sh) && truthy(sh.id)) idx[sh.id] = sh }
            add(i.board)
            for (sh in arr(i["byStatus?"])) add(sh)
            for (sh in arr(i["byPriority?"])) add(sh)
            n._sheets = idx; n._orch = if (truthy(i["orchestration?"])) i["orchestration?"] else null
            if (!truthy(n._cur) || !truthy(idx[n._cur])) n._cur = if (truthy(i.board)) i.board.id else i.board
            obj()
        }, { n, _ -> p.renderConcentric(n) }),
        "graal.heap" to Runner({ n, _ -> val r = api("GET", "/api/graal/heap"); n._heap = r; o("heap" to r) }, { n, _ -> p.renderHeap(n) }),
        "mux.standings" to Runner({ n, _ ->
            val r = api("GET", "/api/mux/standings"); n._standings = if (truthy(r) && truthy(r.standings)) r.standings else jsArray()
            o("standings" to n._standings)
        }, { n, _ -> p.renderStandings(n) }),
    )
}

/**
 * server-family runners: ONE author of execution semantics. The daemon runs the node (POST
 * /api/lcnc/run against the composed lcncRunners registry); the browser posts params+inputs and
 * renders the outputs.
 */
internal fun serverRun(type: String): Runner = Runner({ n, i ->
    val r = api("POST", "/api/lcnc/run", o("type" to type, "params" to (if (truthy(n.params)) n.params else obj()), "inputs" to i))
    if (r.ok != true) refuse(r.error ?: ("lcnc run failed: $type"))
    if (truthy(r.outputs)) r.outputs else obj()
})

internal fun launchJs(block: suspend () -> Unit) { MainScope().launch { block() } }

internal fun esc(s: dynamic): String = PatchHtml.text(str(s))
internal fun escA(s: dynamic): String = PatchHtml.attr(str(s))
internal fun escNullable(s: dynamic): String = PatchHtml.attr(if (s == null) "" else str(s))

internal fun byId(id: String): dynamic = document.getElementById(id).asDynamic()
internal fun q(sel: String): dynamic = document.querySelector(sel).asDynamic()
internal fun now(): Double = js("Date.now()").unsafeCast<Double>()
internal fun later(ms: Int, f: () -> Unit): Int = window.setTimeout(f, ms)
