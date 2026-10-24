package borg.trikeshed.web.pages

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.async
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.CENTER
import org.w3c.dom.CanvasTextAlign
import org.w3c.dom.CanvasTextBaseline
import org.w3c.dom.Element
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLDialogElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLFormElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.LEFT
import org.w3c.dom.MIDDLE
import org.w3c.dom.RIGHT
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.url.URL
import org.w3c.dom.url.URLSearchParams
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import kotlin.js.Date
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

/** Dedicated operator surfaces over the shared mux API (mux.js over mux-core.js): KeyMux, ModelMux, Sessions, Live statistics. */
object MuxPage {
    private val page: String by lazy {
        val p = window.location.pathname
        if (p.contains("keymux")) "keys" else if (p.contains("sessions")) "sessions" else if (p.contains("stats")) "stats" else "models"
    }
    private val titles = mapOf(
        "keys" to ("KeyMux" to "CREDENTIALS & QUOTA"), "models" to ("ModelMux" to "MODEL ROUTING"),
        "sessions" to ("Sessions" to "CONVERSATIONS & BRANCHES"), "stats" to ("Live statistics" to "REQUESTS & QUOTA"),
    )

    private var roster: dynamic = null
    private var catalogRoster: dynamic = null
    private var models: dynamic = null
    private var endpoints: dynamic = null
    private var standings: dynamic = null
    private var calls: dynamic = null
    private var sessions: dynamic = null
    private var detail: dynamic = null
    private var breadcrumbContexts: dynamic = null
    private var breadcrumbContext: String? = null
    private var breadcrumbSubmission: dynamic = null
    private var breadcrumbCalls: dynamic = null
    private var breadcrumbHorizon: dynamic = null
    private var keyedModels: dynamic = null
    private var breadcrumbSession: dynamic = null
    /** context NUL id -> the recall slider's presence and the breadcrumb tool's answer at it. */
    private val recallPresence = HashMap<String, Double>()
    private val recalls = HashMap<String, dynamic>()
    private var recallDrag = false
    private var selected: Double? = null
    private var tab = "conversation"
    private var modelsSelected = LinkedHashSet<String>()
    private var live = true
    private var busy = false
    private var pending = false
    private var mutating = false
    private var lastPoll = 0.0
    private var lastCatalog = 0.0
    private val errors = LinkedHashMap<String, String>()
    private var detailShell: dynamic = null
    private var quotaAt: dynamic = null
    private val htmlCache = HashMap<Element, String>()
    var reel: dynamic = null
    var reelDefaults = false
    var reelSubmitting = false
    var reelVoteSubmitting = false
    var reelSheets: dynamic = null
    var reelSheetId: String? = null
    var reelBoardKey: String? = null
    var reelHistoryId: String? = null
    var breadcrumbIndex: dynamic = null
    var breadcrumbIndexRecall: dynamic = null
    var breadcrumbIndexContext: String? = null
    var breadcrumbIndexBusy = false

    private fun q(s: String): HTMLElement? = document.querySelector(s) as HTMLElement?
    private fun qq(s: String): HTMLElement = document.querySelector(s) as HTMLElement
    private fun selectedContext(): String? = if (page == "sessions") selected?.let { str(it) } else breadcrumbContext

    private fun breadcrumbRows(): List<dynamic> {
        val contexts = list(breadcrumbContexts)
        val known = list(sessions)
        val rows = known.map { session ->
            val retained = contexts.firstOrNull { it.context == str(session.id) }
            val row = js("Object").assign(obj(), retained ?: obj())
            row.context = str(session.id)
            row.title = session.title
            row
        }
        return rows + contexts.filter { context -> known.none { str(it.id) == context.context } }
    }
    private fun rosterRows(): dynamic {
        if (roster == null && catalogRoster == null) return null
        val catalog = list(catalogRoster)
        val matched = mutableSetOf<String>()
        val rows = list(roster).map { row ->
            val model = catalog.firstOrNull {
                it.base == row.base && (it.model == row.model || it.wireModel == row.model)
            }
            if (model == null) row else {
                matched.add(str(model.model))
                val merged = js("Object").assign(obj(), row, model)
                merged.envVar = row.envVar
                merged
            }
        }
        return (rows + catalog.filter { str(it.model) !in matched }).toTypedArray()
    }

    private fun credential(row: dynamic): String = if (row.auth == "none") badge("", "Not required") else
        badge(if (truthy(row.keyPresent)) "good" else "bad", if (truthy(row.keyPresent)) "Present" else "Missing")

    private fun credentialIdentity(row: dynamic): String = if (row.auth == "none") "" else listOfNotNull(
        strOrEmpty(row.keyId).takeIf { it.isNotEmpty() },
        strOrEmpty(row.envVar).takeIf { it.isNotEmpty() && it != strOrEmpty(row.keyId) },
    ).joinToString("") { "<small class=\"mono\">${esc(it)}</small>" }
    private fun esc(v: dynamic): String = MuxCore.escape(strOrEmpty(v))
    private fun icon(name: String) = "<i data-lucide=\"$name\"></i>"
    private fun btn(action: String, label: String, symbol: String, extra: String = "") =
        "<button data-action=\"$action\" title=\"${esc(label)}\" aria-label=\"${esc(label)}\" $extra>${icon(symbol)}</button>"
    private fun list(v: dynamic): List<dynamic> = jsArray(v).toList()
    private fun num(v: dynamic): Double { val n = js("Number")(v) as Double; return if (n.isFinite()) n else 0.0 }
    private fun nowMs(): Double = Date.now()

    private fun html(target: Element?, value: String) {
        if (target == null || htmlCache[target] == value) return
        htmlCache[target] = value
        target.innerHTML = value
    }
    private fun html(selector: String, value: String) = html(q(selector), value)

    private fun icons() {
        val f: dynamic = window.asDynamic().MuxIcons
        if (f != null) f()
    }

    private fun badge(status: dynamic, label: dynamic = status) = "<span class=\"badge ${esc(status)}\">${esc(label)}</span>"
    private fun empty(text: String, symbol: String = "network") = "<div class=\"empty\">${icon(symbol)}${esc(text)}</div>"

    private fun count(v: dynamic): String {
        if (v == null) return "--"
        val n = num(v)
        val opts: dynamic = obj()
        opts.notation = if (abs(n) >= 10000) "compact" else "standard"
        opts.maximumFractionDigits = 1
        val fmt = js("Intl.NumberFormat")
        return js("new fmt(undefined, opts)").format(n) as String
    }

    private fun localeTime(at: Double, opts: dynamic): String = Date(at).asDynamic().toLocaleTimeString(js("[]"), opts) as String

    private fun time(at: dynamic): String = if (!truthy(at)) "--" else localeTime(num(at), js("({hour:'2-digit',minute:'2-digit',second:'2-digit'})"))

    private fun date(at: dynamic): String = if (!truthy(at)) "--" else Date(num(at)).asDynamic().toLocaleString(js("[]"), js("({month:'short',day:'numeric',hour:'2-digit',minute:'2-digit'})")) as String

    private var toastTimer = 0
    private fun toast(message: String) {
        val t = qq("#toast")
        t.textContent = message; t.hidden = false
        window.clearTimeout(toastTimer)
        toastTimer = setTimeout(4000) { t.hidden = true }
    }

    private suspend fun request(path: String, body: dynamic = undefined, method: String = if (body == undefined) "GET" else "POST"): dynamic {
        val init = requestInit(
            method,
            if (body == undefined) obj() else jsonHeaders(),
            if (body == undefined) undefined else JSON.stringify(body),
            "no-store",
            js("AbortSignal").timeout(15000),
        )
        val response = fetchResponse(path, init)
        val data: dynamic
        try { data = response.jsonValue() } catch (_: Throwable) { throw Exception("Invalid response (" + response.status + ")") }
        if (!response.ok) throw Exception(str(if (truthy(data.error)) data.error else if (truthy(data.detail)) data.detail else if (truthy(data.verdict)) data.verdict else "HTTP " + response.status))
        return data
    }

    private fun call(c: dynamic) = MuxCall(
        status = strOrEmpty(c.status), cachedHit = truthy(c.cachedHit), startedAt = num(c.startedAt),
        endedAt = if (c.endedAt == null) null else num(c.endedAt),
        inputTokens = num(c.inputTokens), outputTokens = num(c.outputTokens), cacheReadTokens = num(c.cacheReadTokens), cacheWriteTokens = num(c.cacheWriteTokens),
    )

    private fun totals(cs: List<dynamic>): MuxTotals = MuxCore.totals(cs.map { call(it) })

    private fun quota(s: dynamic): MuxQuota = MuxCore.quota(
        if (s == null) null else MuxStanding(num(s.limit), num(s.spent), num(s.windowMs), num(s.windowStartMs), truthy(s.exhausted), s.usable == false),
        nowMs(),
    )

    private fun isActive(status: dynamic): Boolean = strOrEmpty(status) in MuxCore.ACTIVE

    private fun showErrors() {
        val box = qq("#errors")
        box.hidden = errors.isEmpty()
        box.textContent = errors.entries.joinToString(" | ") { it.key + ": " + it.value }
        val label = if (!live) "Paused" else if (errors.isNotEmpty()) "Connection degraded" else if (lastPoll != 0.0) "Live · " + time(lastPoll) else "Connecting"
        html("#connection", "<span class=\"dot " + (if (errors.isNotEmpty()) "offline" else if (live && lastPoll != 0.0) "live" else "") + "\"></span>" + esc(label))
    }

    private suspend fun poll(force: Boolean = false) {
        if (busy) { pending = pending || force; return }
        if (!force && (!live || document.asDynamic().hidden == true)) return
        busy = true
        val catalog = force || nowMs() - lastCatalog > 20000
        val initialContext = breadcrumbContexts == null && breadcrumbContext == null
        val sources = ArrayList<Triple<String, String, (dynamic) -> Unit>>()
        sources.add(Triple("Activity", "/api/mux/activity") { r -> calls = if (truthy(r.calls)) r.calls else js("[]") })
        sources.add(Triple("Sessions", "/api/mux/sessions") { r -> sessions = if (truthy(r.sessions)) r.sessions else js("[]") })
        sources.add(Triple("Quota", "/api/mux/standings") { r -> standings = if (truthy(r.standings)) r.standings else js("[]"); quotaAt = r.atMs })
        if (page == "models") sources.add(Triple("Evolution reel",
            "/api/mux/reel" + (reelHistoryId?.let { "?runId=" + reelUrl(it) } ?: "")) { r -> reel = r })
        if (page != "stats") {
            sources.add(Triple("Model contexts", "/v1/breadcrumbs") { r ->
                breadcrumbContexts = if (truthy(r.contexts)) r.contexts else js("[]")
            })
        }
        if (catalog) {
            sources.add(Triple("Credentials", "/api/mux/keys") { r -> roster = if (truthy(r.roster)) r.roster else js("[]") })
            sources.add(Triple("Models", "/api/mux/catalog") { r -> models = if (truthy(r.models)) r.models else js("[]"); catalogRoster = if (truthy(r.roster)) r.roster else null })
            sources.add(Triple("Endpoints", "/api/mux/endpoints") { r -> endpoints = if (truthy(r.user)) r.user else js("[]") })
            if (page == "keys") sources.add(Triple("Keyed models", "/api/mux/discovery") { r -> keyedModels = r })
        }
        val jobs = sources.map { (name, path, apply) ->
            pageScope.async {
                try { apply(request(path)); errors.remove(name) } catch (e: Throwable) { errors[name] = e.message ?: "" }
            }
        }
        jobs.forEach { it.await() }
        if (catalog) lastCatalog = nowMs()
        if (page != "sessions" && initialContext && breadcrumbContext == null) {
            val recent = breadcrumbRows().maxByOrNull { num(it.submittedAtMs) }
            breadcrumbContext = if (recent == null) null else str(recent.context)
        }
        if (page != "sessions" && breadcrumbContext != null && breadcrumbRows().none { it.context == breadcrumbContext }) {
            breadcrumbContext = null; breadcrumbSubmission = null; breadcrumbCalls = null; breadcrumbHorizon = null
            errors.remove("Breadcrumbs")
            errors.remove("Context routing")
            errors.remove("Context cleaner")
        }
        if (page == "sessions" && selected == null) { val f = list(sessions).firstOrNull { !truthy(it.archived) }; selected = if (f == null) null else num(f.id) }
        val id = selected
        if (page == "sessions" && id != null) {
            try {
                val d = request("/api/mux/sessions?id=" + str(id))
                if (selected == id) { detail = d; errors.remove("Session") }
            } catch (e: Throwable) { errors["Session"] = e.message ?: "" }
        }
        val context = selectedContext()
        if (page == "sessions") breadcrumbSession = detail
        else if (page != "stats" && context != null && list(sessions).any { str(it.id) == context }) {
            try {
                val value = request("/api/mux/sessions?id=" + (js("encodeURIComponent")(context) as String))
                if (selectedContext() == context) { breadcrumbSession = value; errors.remove("Context session") }
            } catch (e: Throwable) {
                if (selectedContext() == context) { breadcrumbSession = null; errors["Context session"] = e.message ?: "" }
            }
        } else {
            breadcrumbSession = null
            errors.remove("Context session")
        }
        if (page != "stats" && context != null && list(breadcrumbContexts).any { it.context == context }) {
            try {
                val value = request("/v1/breadcrumbs?context=" + (js("encodeURIComponent")(context) as String) + "&view=submitted")
                if (selectedContext() == context) { breadcrumbSubmission = value; errors.remove("Breadcrumbs") }
            } catch (e: Throwable) {
                if (selectedContext() == context) { breadcrumbSubmission = null; errors["Breadcrumbs"] = e.message ?: "" }
            }
            try {
                val value = request("/v1/breadcrumbs?context=" + (js("encodeURIComponent")(context) as String) + "&view=routing")
                if (selectedContext() == context) { breadcrumbCalls = value; errors.remove("Context routing") }
            } catch (e: Throwable) {
                if (selectedContext() == context) { breadcrumbCalls = null; errors["Context routing"] = e.message ?: "" }
            }
            try {
                val value = request("/v1/breadcrumbs?context=" + (js("encodeURIComponent")(context) as String) + "&view=horizon")
                if (selectedContext() == context) { breadcrumbHorizon = value; errors.remove("Context cleaner") }
            } catch (e: Throwable) {
                if (selectedContext() == context) { breadcrumbHorizon = null; errors["Context cleaner"] = e.message ?: "" }
            }
        } else if (page != "stats") {
            breadcrumbSubmission = null; breadcrumbCalls = null; breadcrumbHorizon = null
            errors.remove("Breadcrumbs")
            errors.remove("Context routing")
            errors.remove("Context cleaner")
        }
        lastPoll = nowMs(); busy = false; render()
        if (pending) { pending = false; launchPage { poll(true) } }
    }

    private fun metric(label: String, value: String, note: String, symbol: String) =
        "<div class=\"metric\"><div class=\"metric-label\">${icon(symbol)}${esc(label)}</div><div class=\"metric-value\">${esc(value)}</div><div class=\"metric-note\">${esc(note)}</div></div>"

    private fun metrics() {
        val cs = list(calls)
        val t = totals(cs)
        val ss = if (sessions == null) null else list(sessions)
        val active = ss?.count { isActive(it.status) && !truthy(it.parentId) }
        val ros = rosterRows()
        val ready = if (ros != null) list(ros).filter { it.auth != "none" && truthy(it.keyPresent) }
            .map { str(if (truthy(it.keyId)) it.keyId else if (truthy(it.envVar)) it.envVar else it.name) }.toSet().size else null
        val st = if (standings == null) null else list(standings)
        val rows: List<List<String>> = if (page == "keys") listOf(
            listOf("Credentials present", count(ready), if (ros != null) list(ros).size.toString() + " roster entries" else "Waiting for credentials", "key-round"),
            listOf("Observed quota pools", count(st?.size), count(st?.count { num(it.limit) > 0 }) + " with known limits", "network"),
            listOf("Exhausted pools", count(st?.count { truthy(it.exhausted) || !truthy(it.usable) }), "Provider quota windows", "clock"),
            listOf("Provider tokens", if (calls != null) count(t.input + t.output) else "--", "Retained attempts, excluding local cache", "chart-no-axes-combined"),
        ) else listOf(
            listOf("Active sessions", count(active), count(ss?.count { isActive(it.status) && truthy(it.parentId) }) + " active branches", "messages-square"),
            listOf("Model attempts", if (calls != null) count(t.attempts) else "--", "${t.running} in flight · ${t.failed} failed", "network"),
            listOf("Provider tokens", if (calls != null) count(t.input + t.output) else "--", count(t.input) + " input · " + count(t.output) + " output", "chart-no-axes-combined"),
            listOf("Median latency", MuxCore.duration(t.medianMs), "${t.cached} local cache hits · retained attempts", "clock"),
        )
        html("#metrics", rows.joinToString("") { metric(it[0], it[1], it[2], it[3]) })
    }

    private fun catalogStanding(row: dynamic): dynamic {
        if (row.auth == "none") return null
        if (truthy(row.keyId)) return list(standings).firstOrNull { it.keyId == row.keyId }
        val cs = list(calls).filter { it.model == row.model }
        val keyIds = cs.mapNotNull { if (truthy(it.keyId)) str(it.keyId) else null }.toSet()
        val providers = cs.map { strOrEmpty(it.provider) }.toSet()
        val matches = list(standings).filter { (truthy(it.keyId) && str(it.keyId) in keyIds) || strOrEmpty(it.provider) in providers || it.provider == row.name || it.provider == row.model }
        return matches.firstOrNull { truthy(it.usable) } ?: matches.firstOrNull()
    }

    private fun quotaCell(s: dynamic): String {
        val qv = quota(s)
        if (s == null) return "<span class=\"muted\">Not observed</span>"
        if (!qv.known) return "<span class=\"muted\">Limit unknown</span><small>" + count(s.spent) + " tokens spent</small>"
        val used = qv.used ?: 0.0
        return count(s.spent) + " / " + count(s.limit) + "<span class=\"progress " + (if (qv.status == "exhausted") "bad" else if (used > .8) "warn" else "") +
            "\"><span style=\"width:" + str(used * 100) + "%\"></span></span>"
    }

    /** provider -> key -> models, as each provider's /models endpoint answered under its resolved key. */
    private fun keyedTree(): String {
        val tree = keyedModels ?: return empty(if (errors.containsKey("Keyed models")) "Providers could not be asked" else "Asking providers for their models", "key-round")
        val providers = list(tree.providers)
        if (providers.isEmpty()) return empty("No provider key resolves in this daemon", "key-round")
        return "<p class=\"muted\">Listed ${esc(time(tree.atMs))}.</p>" + providers.joinToString("") { p ->
            val ms = list(p.models)
            val routed = ms.count { truthy(it.inRoster) }
            "<details class=\"keyed-provider\"><summary><strong>${esc(p.provider)}</strong> <span class=\"mono\">${esc(p.base)}</span> " +
                "<span class=\"mono\">${esc(p.key)}</span> <small>${esc(p.keySource)}</small> " +
                (if (truthy(p.error)) badge("bad", p.error) else badge("good", "${ms.size} models · $routed in roster")) + "</summary>" +
                (if (ms.isEmpty()) "" else "<div class=\"table-wrap\"><table><thead><tr><th>Model</th><th>Context</th><th>Tier</th><th>Routing</th></tr></thead><tbody>" +
                    ms.joinToString("") { m ->
                        "<tr><td class=\"mono\">${esc(m.id)}</td><td class=\"mono\">${if (num(m.contextWindow) > 0) count(m.contextWindow) else "--"}</td>" +
                            "<td>${if (truthy(m.freeTier)) badge("good", "Free") else badge("", "Paid or unquoted")}</td>" +
                            "<td>${if (truthy(m.inRoster)) badge("good", "In roster") else badge("", "Listed only")}</td></tr>"
                    } + "</tbody></table></div>") + "</details>"
        }
    }

    private fun renderKeys() {
        html("#discoveryRows", keyedTree())
        val search = (qq("#keySearch") as HTMLInputElement).value.lowercase()
        val status = (qq("#keyStatus") as HTMLSelectElement).value
        val rows = list(rosterRows()).filter {
            listOf(strOrEmpty(it.name), strOrEmpty(it.model), strOrEmpty(it.keyId), strOrEmpty(it.envVar)).joinToString(" ").lowercase().contains(search) &&
                (status == "all" || (if (status == "ready") it.auth != "none" && truthy(it.keyPresent)
                else if (status == "none") it.auth == "none" else it.auth != "none" && !truthy(it.keyPresent)))
        }
        html("#keyRows", if (rows.isNotEmpty()) rows.joinToString("") { r ->
            val s = catalogStanding(r)
            val qv = quota(s)
            val present = truthy(r.keyPresent)
            val noCredential = r.auth == "none"
            val catalogued = list(models).any { it.model == r.model && it.base == r.base }
            val exhausted = s != null && !truthy(s.usable)
            "<tr><td><strong>${esc(r.name)}</strong><small class=\"mono\">${esc(r.model)}</small></td><td>" +
                credential(r) + credentialIdentity(r) + "</td><td>" +
                badge(if (exhausted) "bad" else "", if (exhausted) "Exhausted" else if (catalogued && (present || noCredential)) "In roster" else if (catalogued) "Unbound" else "Outside model roster") + "</td><td>" + quotaCell(s) +
                "</td><td class=\"mono\">" + (if (qv.status == "refreshing") "Refreshing" else MuxCore.duration(qv.resetMs)) + "</td><td class=\"mono\">" + count(s?.accessCount) + "</td></tr>"
        } else "<tr><td colspan=\"6\">" + empty(if (roster != null) "No matching credentials" else "Credential roster unavailable", "key-round") + "</td></tr>")
        val eps = list(endpoints)
        html("#endpointRows", if (eps.isNotEmpty()) eps.mapIndexed { index, r ->
            val bound = list(models).any { it.base == r.base && (it.model == r.model || it.wireModel == r.model) }
            val enabled = r.flags?.enabled != false
            val hosted = enabled && r.auth == "none" && bound
            "<tr><td><strong>${esc(r.name)}</strong><small>${esc(r.base)}</small></td><td class=\"mono\">${esc(r.model)}</td><td class=\"mono\">${if (r.auth == "none") "None" else esc(r.envVar)}</td><td>" +
                badge(if (enabled) "good" else "", if (enabled) "Enabled" else "Disabled") + (if (truthy(r.flags?.preferred)) " " + badge("", "Preferred") else "") + "</td><td>" +
                badge(if (bound || hosted) "good" else "queued", if (hosted) "Hosted" else if (bound) "In roster" else "Registry only") + "</td><td><div class=\"toolbar\">" +
                btn("edit-endpoint", "Edit " + strOrEmpty(r.name), "pencil", "data-index=\"$index\"") + btn("delete-endpoint", "Remove " + strOrEmpty(r.name), "trash-2", "data-index=\"$index\"") + "</div></td></tr>"
        }.joinToString("") else "<tr><td colspan=\"6\">" + empty(if (endpoints != null) "No registered endpoints" else "Endpoint registry unavailable", "key-round") + "</td></tr>")
    }

    private fun renderModelRows() {
        val search = (qq("#modelSearch") as HTMLInputElement).value.lowercase()
        val all = list(models)
        val rows = all.filter { (strOrEmpty(it.name) + " " + strOrEmpty(it.model)).lowercase().contains(search) }
        qq("#modelCount").textContent = if (models != null) all.size.toString() else ""
        html("#modelRows", if (rows.isNotEmpty()) rows.joinToString("") { m ->
            val model = strOrEmpty(m.model)
            val sel = model in modelsSelected
            val t = totals(list(calls).filter { it.model == m.model })
            "<tr class=\"" + (if (sel) "selected" else "") + "\"><td class=\"check-cell\"><input type=\"checkbox\" data-model=\"${esc(model)}\" aria-label=\"Select ${esc(model)}\" " +
                (if (sel) "checked" else "") + "></td><td><strong class=\"mono\" title=\"${esc(model)}\">${esc(model)}</strong><small>${esc(m.name)}</small></td><td>" +
                credential(m) + credentialIdentity(m) + "</td><td class=\"mono\">" + count(t.attempts) + "</td><td class=\"mono\">" + MuxCore.duration(t.medianMs) + "</td></tr>"
        } else "<tr><td colspan=\"5\">" + empty(if (models != null) "No matching models" else "Model roster unavailable") + "</td></tr>")
        val runSession = qq("#runSession") as HTMLSelectElement
        val prior = runSession.value
        html(runSession, "<option value=\"\">New session</option>" + list(sessions).filter { !truthy(it.archived) && !isActive(it.status) && !truthy(it.parentId) }
            .joinToString("") { "<option value=\"" + str(it.id) + "\">" + esc(it.title) + "</option>" })
        val opts = runSession.options
        if ((0 until opts.length).any { (opts.item(it).asDynamic().value as String) == prior }) runSession.value = prior
        selection()
    }

    private fun breadcrumbMetadata(content: dynamic): String {
        if (!isString(content)) return ""
        val value: dynamic = try { JSON.parse<dynamic>(str(content)) } catch (_: Throwable) { return "" }
        fun projection(row: dynamic): Boolean = row != null && isString(row.id) && isString(row.source) &&
            isNumber(row.presence) && (row.presence as Double).isFinite() && num(row.presence) in 0.0..1.0 &&
            (row.content == null || isString(row.content))
        val rows: List<dynamic> = if (projection(value)) listOf<dynamic>(value) else if (value != null && isString(value.skill))
            list(value.breadcrumbs).filter { projection(it) } else emptyList()
        return rows.joinToString("") { row ->
            "<div class=\"breadcrumb-fields\"><span>ID <b class=\"mono\">${esc(row.id)}</b></span>" +
                "<span>Source <b class=\"mono\">${esc(row.source)}</b></span><span>Presence <b>${esc(row.presence)}</b></span></div>"
        }
    }

    /**
     * The context cleaner over the breadcrumb trail: the overflow band, then every record in the order it was laid, a turn's
     * breadcrumbs inside it, each holding its particulate summary at its current presence and a recall from 0 to 1.
     */
    private fun cleaner(context: String?): String {
        val h = breadcrumbHorizon
        if (context == null) return empty("Select a model session or context to inspect its context cleaner", "eraser")
        if (h == null || h.context != context) return empty(
            if (errors.containsKey("Context cleaner")) "Context cleaner unavailable" else "Loading context cleaner", "eraser")
        val band = str(h.band)
        val tone = when (band) { "scale_back" -> "bad"; "hold" -> "queued"; else -> "good" }
        val rate = num(h.errorRate)
        val head = "<div class=\"detail-meta\"><span>Band ${badge(tone, band.replace('_', ' '))}</span>" +
            "<span>Overflow rate <b>${esc((rate * 100).asDynamic().toFixed(1))}%</b> of ${count(h.turns)} / ${count(h.window)} turns</span>" +
            "<span>Last prompt <b>${count(h.lastTokens)}</b> of ${count(h.budgetTokens)} tokens</span>" +
            (if (truthy(h.bandAtMs)) "<span>Since ${esc(time(h.bandAtMs))}</span>" else "") + "</div>"
        val rows = list(h.records)
        if (rows.isEmpty()) return head + empty("No records are retained in this context", "eraser")
        return head + "<ol class=\"trail\">" + rows.withIndex().joinToString("") { (index, r) ->
            val id = str(r.id)
            val key = context + "\u0000" + id
            val presence = num(r.presence)
            val gain = num(r.gain)
            val depth = num(r.depth)
            val bar = if (presence < 0.3) "bad" else if (presence < 0.7) "warn" else ""
            val chosen = recallPresence[key] ?: (round(presence * 20) / 20)
            val recalled = recalls[key]
            val particles = strOrEmpty(r.content)
            "<li class=\"crumb\" style=\"margin-left:${depth * 22}px\"><div class=\"crumb-head\"><span class=\"mono muted\">${index + 1}</span>" +
                "<strong class=\"mono\" title=\"${esc(id)}\">${if (depth > 0) "↳ " else ""}${esc(id)}</strong>" +
                "<small class=\"mono\">${esc(r.source)}${if (num(r.children) > 0) " · carries ${count(r.children)}" else ""}</small>" +
                "<span class=\"progress $bar\"><span style=\"width:${(presence * 100).asDynamic().toFixed(1)}%\"></span></span>" +
                "<small>presence ${esc(presence.asDynamic().toFixed(3))}</small>" +
                (if (gain == 0.0) badge("bad", "dropped") else "<small>gain ${esc(gain.asDynamic().toFixed(3))}</small>") +
                "<small>${MuxCore.duration(num(r.ageMs))} · ${count(r.chars)} chars</small></div>" +
                "<p class=\"crumb-particles\">${if (particles.isEmpty()) "<span class=\"muted\">Reference only</span>" else esc(particles)}</p>" +
                "<label class=\"crumb-recall\">Recall<input type=\"range\" min=\"0\" max=\"1\" step=\"0.05\" value=\"$chosen\" data-recall=\"${esc(id)}\" " +
                "aria-label=\"Recall ${esc(id)} from 0 to 1 of its original\"><output>${chosen.asDynamic().toFixed(2)}</output></label>" +
                (if (recalled == null) "" else "<pre class=\"crumb-recalled\" aria-label=\"Recalled at ${esc(recalled.presence)}\">" +
                    (if (recalled.content == null) "Reference only" else esc(recalled.content)) + "</pre>") + "</li>"
        } + "</ol>"
    }

    private fun crumb(rows: dynamic, id: String): dynamic {
        for (row in list(rows)) {
            if (row.id == id) return row
            val nested = crumb(row.breadcrumbs, id)
            if (nested != null) return nested
        }
        return null
    }

    /** The breadcrumb tool from the panel: resize [id] to [presence] of its original and keep the trail's answer. */
    private suspend fun recall(context: String, id: String, presence: Double) {
        val body: dynamic = obj(); body.context = context; body.id = id; body.presence = presence
        try {
            val found = crumb(request("/v1/breadcrumbs", body).breadcrumbs, id)
            val r: dynamic = obj(); r.presence = presence; r.content = found?.content
            recalls[context + "\u0000" + id] = r
            errors.remove("Recall")
        } catch (e: Throwable) { errors["Recall"] = e.message ?: "" }
        poll(true)
    }

    private fun renderBreadcrumbs() {
        val contexts = breadcrumbRows()
        val selector = qq("#breadcrumbContext") as HTMLSelectElement
        html(selector, "<option value=\"\">Select a context</option>" + contexts.joinToString("") {
            "<option value=\"${esc(it.context)}\">" +
                (if (truthy(it.title)) "${esc(it.title)} · " else "") + esc(it.context) +
                (if (it.records != null) " · ${count(it.records)} retained" else " · No retained context") + "</option>"
        })
        val context = selectedContext()
        selector.value = context ?: ""
        selector.disabled = contexts.isEmpty()
        val runTarget = qq("#breadcrumbRunTarget")
        runTarget.hidden = page != "models"
        if (page == "models") {
            val runSession = qq("#runSession") as HTMLSelectElement
            val target = if (runSession.value.isEmpty()) "a new session" else
                "session " + strOrEmpty(runSession.options.item(runSession.selectedIndex)?.textContent)
            runTarget.textContent = if (context != null && runSession.value == context) "Runs continue this session."
                else (if (context != null) "Inspection only. " else "") + "Run target: $target."
        }
        val submission = breadcrumbSubmission
        val session = list(sessions).firstOrNull { str(it.id) == context }
        html("#breadcrumbMeta", (if (session != null) "<span>Session <b>${esc(session.title)}</b></span>" else "") +
            (if (context != null) "<span>Context <b class=\"mono\">${esc(context)}</b></span>" else "") +
            if (submission != null && submission.context == context && submission.atMs != null)
            "<span>Model <b class=\"mono\">${esc(submission.model)}</b></span><span>Submitted ${esc(date(submission.atMs))}</span>" +
                "<span>${count(submission.messageCount)} tool messages</span>" else "")
        val contextCalls: List<dynamic> = if (context == null) emptyList() else {
            val stored = if (breadcrumbSession != null && str(breadcrumbSession.id) == context) list(breadcrumbSession.calls) else emptyList()
            val retained = if (breadcrumbCalls != null && breadcrumbCalls.context == context) list(breadcrumbCalls.calls) else emptyList()
            val liveCalls = list(calls).filter {
                it.contextId == context || (it.conversationId != null && str(it.conversationId) == context)
            }
            MuxCore.uniqueBy(liveCalls + stored + retained) {
                strOrEmpty(it.model) + ":" + strOrEmpty(it.id) + ":" + strOrEmpty(it.startedAt)
            }
                .sortedBy { num(it.startedAt) }
        }
        html("#breadcrumbRouting", if (context == null) empty("Select a model session or context to inspect routing")
            else attemptsTable(contextCalls))
        if (!recallDrag) html("#breadcrumbCleaner", cleaner(context))
        val content = when {
            breadcrumbContexts == null -> empty("Model contexts unavailable", "messages-square")
            contexts.isEmpty() -> empty("No model sessions or contexts are available", "messages-square")
            context == null -> empty("Select a model session or context", "messages-square")
            list(breadcrumbContexts).none { it.context == context } -> empty("No submitted model context is retained for this session", "messages-square")
            submission == null || submission.context != context -> empty(
                if (errors.containsKey("Breadcrumbs")) "Submitted context unavailable" else "Loading submitted context", "messages-square")
            submission.atMs == null || submission.reason == "not_submitted" -> empty("No model request has been submitted for this context", "messages-square")
            submission.exact != true -> empty(
                if (submission.reason == "capacity") "Submitted tool messages exceeded the inspection limit; their content is unavailable"
                else "Exact submitted tool messages are unavailable", "messages-square")
            list(submission.messages).isEmpty() -> empty("The latest model request contained no tool messages", "messages-square")
            else -> list(submission.messages).joinToString("") { message ->
                val body = if (isString(message.content)) str(message.content) else JSON.stringify(message.content, null, 2)
                "<article class=\"breadcrumb-message\"><h3>Tool result <span class=\"mono\">${esc(message.tool_call_id)}</span></h3>" +
                    breadcrumbMetadata(message.content) + "<pre>${esc(body)}</pre></article>"
            }
        }
        html("#breadcrumbMessages", content)
    }

    private fun selection() {
        val ms = modelsSelected.toList()
        val mode = (qq("#runMode") as HTMLSelectElement).value
        html("#selectionSummary", if (ms.isNotEmpty()) ms.joinToString("") {
            "<div class=\"selected-model\"><span class=\"mono\">${esc(it)}</span>" + btn("deselect-model", "Deselect $it", "x", "data-model=\"${esc(it)}\"") + "</div>"
        } else "Automatic routing")
        val extra = if (mode == "synthesize") 1 else 0
        val lanes = if (mode == "chat") 1 else ms.size
        val maxTokens = (js("Number")((qq("#maxTokens") as HTMLInputElement).value) as Double).let { if (it.isNaN()) 0.0 else it }
        qq("#runAllocation").textContent = "$lanes lane" + (if (lanes != 1) "s" else "") + (if (extra != 0) " + synthesis" else "") + " · " + count((lanes + extra) * maxTokens) + " output cap"
        icons()
    }

    private fun attemptsTable(cs: List<dynamic>): String {
        if (cs.isEmpty()) return empty("No attempts in this view", "chart-no-axes-combined")
        return "<div class=\"table-wrap\"><table><thead><tr><th>Time / session</th><th>Model / provider / key</th><th>Status</th><th>Input</th><th>Output</th><th>Latency</th><th>Cache</th></tr></thead><tbody>" +
            cs.reversed().joinToString("") { c ->
                "<tr><td><strong class=\"mono\">" + time(c.startedAt) + "</strong><small>" +
                    (if (truthy(c.conversationId)) "<a href=\"/mux/sessions?id=" + str(c.conversationId) + "\">Session " + str(c.conversationId) + "</a>" else esc(if (truthy(c.contextId)) c.contextId else "External call")) +
                    "</small>" + (if (truthy(c.assessmentId)) "<small>Assessment ${esc(c.assessmentId)}</small>" else "") +
                    "</td><td><strong class=\"mono\" title=\"${esc(c.model)}\">${esc(c.model)}</strong><small>${esc(c.provider)}</small><small class=\"mono\">" + esc(if (truthy(c.keyId)) c.keyId else "No credential recorded") +
                    "</small></td><td>" + badge(c.status) + "<small>" + esc(if (truthy(c.httpStatus)) "HTTP " + str(c.httpStatus) else if (truthy(c.error)) c.error else "") +
                    "</small></td><td class=\"mono\">" + count(c.inputTokens) + "</td><td class=\"mono\">" + count(c.outputTokens) + "</td><td class=\"mono\">" +
                    MuxCore.duration((if (c.endedAt == null) nowMs() else num(c.endedAt)) - num(c.startedAt)) + "</td><td>" +
                    badge(if (truthy(c.cachedHit)) "good" else "", if (truthy(c.cachedHit)) "Local hit" else "Provider") +
                    (if (truthy(c.cacheReadTokens)) "<small>" + count(c.cacheReadTokens) + " read tokens</small>" else "") + "</td></tr>"
            } + "</tbody></table></div>"
    }

    private fun renderRecent() {
        val ss = list(sessions)
        val active = ss.count { isActive(it.status) }
        qq("#activeCount").textContent = if (active != 0) active.toString() else ""
        html("#recentSessions", ss.filter { !truthy(it.archived) && !truthy(it.parentId) }.take(7).joinToString("") {
            "<a class=\"recent-session\" href=\"/mux/sessions?id=" + str(it.id) + "\"><span class=\"dot " + esc(it.status) + "\"></span><span>" + esc(it.title) + "</span></a>"
        }.ifEmpty { "<div class=\"muted\" style=\"padding:10px;font-size:11px\">No sessions yet</div>" })
    }

    private fun sessionCalls(): List<dynamic> =
        MuxCore.uniqueBy(list(detail?.calls) + list(calls).filter { it.conversationId != null && num(it.conversationId) == selected }) {
            strOrEmpty(it.conversationId) + ":" + strOrEmpty(it.id) + ":" + strOrEmpty(it.startedAt)
        }

    private fun renderSessions() {
        val ss = list(sessions)
        val search = (qq("#sessionSearch") as HTMLInputElement).value
        val statusFilter = (qq("#sessionStatus") as HTMLSelectElement).value
        val rows = ss.filter { MuxCore.sessionMatches(strOrEmpty(it.title), strOrEmpty(it.model), strOrEmpty(it.id), truthy(it.archived), strOrEmpty(it.status), search, statusFilter) }
        html("#sessionRows", rows.joinToString("") {
            "<button class=\"session-row\" data-action=\"select-session\" data-id=\"" + str(it.id) + "\" aria-current=\"" + (num(it.id) == selected) + "\"><strong>" + esc(it.title) +
                "</strong><span class=\"meta\"><span class=\"dot " + esc(it.status) + "\"></span>" + esc(it.status) + "<span>" + date(it.updatedAt) + "</span></span></button>"
        }.ifEmpty { empty(if (sessions != null) "No matching sessions" else "Sessions unavailable", "messages-square") })
        val s = detail
        if (s == null || num(s.id) != selected) {
            html("#sessionDetail", empty(if (selected != null) "Loading session" else "No session selected", "messages-square"))
            detailShell = null
            return
        }
        if (detailShell != s.id) {
            html("#sessionDetail", "<div class=\"detail-heading\"><h2 id=\"detailTitle\"></h2><div class=\"toolbar\">" + btn("rename", "Rename session", "pencil") +
                btn("fork", "Fork session", "git-branch") + btn("export-session", "Export session", "download") + btn("archive", "Archive session", "archive") +
                "</div></div><div id=\"detailMeta\" class=\"detail-meta\"></div><div class=\"session-tabs\" role=\"tablist\" aria-label=\"Session views\"><button role=\"tab\" data-action=\"session-tab\" data-tab=\"conversation\">Conversation</button><button role=\"tab\" data-action=\"session-tab\" data-tab=\"branches\">Branches</button><button role=\"tab\" data-action=\"session-tab\" data-tab=\"receipts\">Receipts</button></div><div id=\"sessionPanel\" role=\"tabpanel\"></div><form id=\"followupForm\" class=\"followup\"><textarea id=\"followupPrompt\" aria-label=\"Continue session\" placeholder=\"Continue this session...\" maxlength=\"32000\" required></textarea><div class=\"toolbar\"><select id=\"followupModel\" aria-label=\"Reply model\"></select><div class=\"toolbar\">" +
                btn("cancel", "Stop run", "square", "class=\"danger\"") + "<button type=\"submit\" class=\"primary\" aria-label=\"Send message\" title=\"Send message\">" + icon("arrow-up") + "</button></div></div></form>")
            (qq("#followupForm") as HTMLFormElement).onsubmit = { e: Event -> launchPage { sendFollowup(e) }; null }
            detailShell = s.id
        }
        qq("#detailTitle").textContent = strOrEmpty(s.title)
        val t = totals(sessionCalls())
        val childIds = list(s.children).map { num(it) }.toSet()
        val children = ss.filter { num(it.id) in childIds }
        val tokens = t.input + t.output + children.sumOf { num(it.inputTokens) + num(it.outputTokens) }
        html("#detailMeta", badge(s.status) + "<span>Session " + str(s.id) + "</span><span>" + count(tokens) + " provider tokens" + (if (children.isNotEmpty()) " incl. branches" else "") +
            "</span><span>" + esc(s.mode) + "</span>" + (if (truthy(s.parentId)) "<a href=\"/mux/sessions?id=" + str(s.parentId) + "\">" + icon("git-branch") + " Parent session</a>" else "") +
            (if (truthy(s.error)) "<span class=\"danger\">" + esc(s.error) + "</span>" else ""))
        val tabs = document.querySelectorAll("[data-action=session-tab]")
        for (i in 0 until tabs.length) {
            val b = tabs.item(i) as HTMLElement
            val on = tab == b.getAttribute("data-tab")
            b.setAttribute("aria-selected", on.toString()); b.tabIndex = if (on) 0 else -1
        }
        val running = isActive(s.status)
        for (action in listOf("rename", "fork", "archive")) (qq("#sessionDetail [data-action=$action]") as HTMLButtonElement).disabled = running
        val archive = qq("#sessionDetail [data-action=archive]")
        archive.title = if (truthy(s.archived)) "Restore session" else "Archive session"; archive.setAttribute("aria-label", archive.title)
        qq("#followupForm").hidden = truthy(s.archived)
        (qq("#followupForm button[type=submit]") as HTMLButtonElement).disabled = running
        (qq("#followupPrompt") as HTMLTextAreaElement).disabled = running
        qq("#followupForm [data-action=cancel]").hidden = !running
        val modelSelect = qq("#followupModel") as HTMLSelectElement
        val current = modelSelect.value
        html(modelSelect, "<option value=\"\">Automatic routing</option>" + list(models).map { strOrEmpty(it.model) }.toCollection(LinkedHashSet()).joinToString("") { "<option>" + esc(it) + "</option>" })
        val mopts = modelSelect.options
        if ((0 until mopts.length).any { (mopts.item(it).asDynamic().value as String) == current }) modelSelect.value = current
        val content = when (tab) {
            "conversation" -> {
                val messages = list(s.messages)
                "<div class=\"transcript\">" + (if (messages.isNotEmpty()) messages.joinToString("") { m ->
                    "<article class=\"message " + esc(m.role) + "\"><div class=\"message-header\">" + icon(if (m.role == "user") "message-square" else "network") + "<b>" +
                        esc(if (m.role == "user") "You" else if (truthy(m.model)) m.model else "ModelMux") + "</b><span>" + time(m.at) + "</span></div><div class=\"message-content\">" +
                        MuxCore.messageContent(str(m.content)) + "</div></article>"
                } else empty("No messages yet", "message-square")) +
                    (if (running) "<div class=\"message\"><span class=\"badge running\">" + esc(s.status) + "</span><span class=\"muted\"> ${t.running} provider attempts in flight</span></div>" else "") + "</div>"
            }
            "receipts" -> attemptsTable(sessionCalls())
            else -> "<div class=\"graph-root\">" + icon("git-branch") + "<b>" + esc(s.title) + "</b>" + badge(s.status) + "</div>" +
                (if (children.isNotEmpty()) "<div class=\"branch-list\">" + children.joinToString("") { c ->
                    "<a class=\"branch\" href=\"/mux/sessions?id=" + str(c.id) + "\"><div><strong>" + esc(if (truthy(c.model)) c.model else c.title) + "</strong><small>" +
                        count(num(c.inputTokens) + num(c.outputTokens)) + " tokens · " + date(c.updatedAt) + "</small></div>" + badge(c.status) + "</a>"
                } + "</div><div class=\"graph-root\">" + icon("git-merge") + "<span>" + (if (s.mode == "synthesize") "Synthesis" else "Collected responses") + "</span>" + badge(s.status) + "</div>"
                else empty("No fan-out branches", "git-branch"))
        }
        val transcript = q(".transcript")
        val bottom = transcript == null || transcript.scrollHeight - transcript.clientHeight - transcript.scrollTop < 60
        html("#sessionPanel", content)
        val tr = q(".transcript")
        if (bottom && tr != null) tr.scrollTop = tr.scrollHeight.toDouble()
    }

    private fun renderStats() {
        val minutes = js("Number")((qq("#statsWindow") as HTMLSelectElement).value) as Double
        val start = nowMs() - minutes * 60000
        val cs = list(calls).filter { num(if (it.endedAt == null) it.startedAt else it.endedAt) >= start }
        val status = (qq("#attemptStatus") as HTMLSelectElement).value
        html("#statsActivity", attemptsTable(cs.filter { status == "all" || it.status == status }))
        qq("#quotaAt").textContent = if (truthy(quotaAt)) "Observed " + time(quotaAt) else "Quota unavailable"
        val pools = list(standings)
        val groups = LinkedHashMap<String, MutableList<dynamic>>()
        for (c in cs) groups.getOrPut(strOrEmpty(c.model)) { ArrayList() }.add(c)
        val ss = list(sessions).filter { num(it.updatedAt) >= start && !truthy(it.parentId) }
        html("#quotaFlow", "<div class=\"flow-column\"><div class=\"flow-title\">KEY POOLS " + icon("chevron-right") + "</div>" +
            pools.take(10).joinToString("") { s ->
                "<div class=\"flow-item\"><span>" + esc(s.provider) + "<small class=\"muted mono\" style=\"display:block;margin-top:5px\">" + esc(s.keyId) + "</small></span><b>" +
                    (if (num(s.limit) > 0) count(s.remaining) + " left" else "Unknown limit") + "</b></div>"
            }.ifEmpty { empty("No observed pools", "key-round") } +
            "</div><div class=\"flow-column\"><div class=\"flow-title\">MODEL DISPATCH " + icon("chevron-right") + "</div>" +
            groups.entries.take(10).joinToString("") { (model, l) -> "<div class=\"flow-item\"><span>" + esc(model) + "</span><b>" + l.size + " calls</b></div>" }.ifEmpty { empty("No model dispatches") } +
            "</div><div class=\"flow-column\"><div class=\"flow-title\">SESSION FAN-IN " + icon("git-merge") + "</div>" +
            ss.take(10).joinToString("") { s ->
                "<a class=\"flow-item\" href=\"/mux/sessions?id=" + str(s.id) + "\"><span>" + esc(s.title) + "<small class=\"muted\" style=\"display:block;margin-top:5px\">" +
                    list(s.children).size + " branches</small></span>" + badge(s.status) + "</a>"
            }.ifEmpty { empty("No sessions in this window", "messages-square") } + "</div>")
        drawChart(cs, minutes)
    }

    private fun drawChart(cs: List<dynamic>, minutes: Double) {
        val canvas = qq("#trafficChart") as HTMLCanvasElement
        val rect = canvas.getBoundingClientRect()
        if (rect.width == 0.0) return
        val dpr = window.devicePixelRatio.let { if (it == 0.0) 1.0 else it }
        canvas.width = (rect.width * dpr).toInt(); canvas.height = (rect.height * dpr).toInt()
        val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
        ctx.scale(dpr, dpr)
        val bins = MuxCore.buckets(cs.map { call(it) }, minutes, nowMs())
        val maxV = max(4, bins.maxOf { it.completed + it.failed + it.cached }).toDouble()
        val left = 30.0; val right = 10.0; val top = 10.0; val bottom = 28.0
        val width = rect.width - left - right; val height = rect.height - top - bottom
        ctx.font = "10px -apple-system, sans-serif"; ctx.textBaseline = CanvasTextBaseline.MIDDLE
        for (i in 0..4) {
            val y = top + height * i / 4
            ctx.strokeStyle = "#e3e8ea"; ctx.beginPath(); ctx.moveTo(left, y); ctx.lineTo(rect.width - right, y); ctx.stroke()
            ctx.fillStyle = "#677179"; ctx.fillText(round(maxV * (1 - i / 4.0)).toLong().toString(), 2.0, y)
        }
        val step = width / bins.size
        bins.forEachIndexed { i, bin ->
            var y = top + height
            for ((value, color) in listOf(bin.completed to "#087f6b", bin.failed to "#ba3544", bin.cached to "#346bc2")) {
                val h = value / maxV * height
                ctx.fillStyle = color; ctx.fillRect(left + i * step + 2, y - h, max(2.0, step - 4), h); y -= h
            }
        }
        for (i in listOf(0, bins.size / 2, bins.size - 1)) {
            ctx.fillStyle = "#677179"
            ctx.textAlign = if (i == bins.size - 1) CanvasTextAlign.RIGHT else CanvasTextAlign.LEFT
            ctx.fillText(localeTime(bins[i].at, js("({hour:'2-digit',minute:'2-digit'})")), left + i * step + (if (i == bins.size - 1) step else 0.0), rect.height - 10)
        }
        if (cs.isEmpty()) {
            ctx.textAlign = CanvasTextAlign.CENTER; ctx.fillStyle = "#677179"
            ctx.fillText("No completed requests in this window", rect.width / 2, rect.height / 2)
        }
    }

    fun reelUrl(value: dynamic): String = js("encodeURIComponent")(strOrEmpty(value)) as String

    fun renderBreadcrumbIndex() {
        if (breadcrumbIndexContext != selectedContext()) {
            breadcrumbIndex = null; breadcrumbIndexRecall = null
        }
        val index: dynamic = breadcrumbIndex
        html("#breadcrumbIndexResults", if (index == null) "" else
            "<p class=\"muted\">${count(index.total)} matches${if (num(index.omitted) > 0) " · ${count(index.omitted)} omitted" else ""}</p>" +
                "<ol class=\"breadcrumb-index-results\">" + list(index.matches).joinToString("") { row ->
                    "<li><header><span class=\"badge mono\">${esc(row.ref)}</span><strong>${esc(row.title)}</strong>" +
                        "<button data-action=\"breadcrumb-index-recall\" data-ref=\"${esc(row.ref)}\" data-cid=\"${esc(row.cid)}\">Recall ranges</button></header>" +
                        "<small>${esc(row.source)}</small><small class=\"mono\">${esc(row.cid)}</small>" +
                        (if (list(row.children).isEmpty()) "" else "<small>Children: " + list(row.children).joinToString(" · ") {
                            "<span class=\"mono\" title=\"${esc(it.cid)}\">${esc(it.ref)}</span>"
                        } + "</small>") + "</li>"
                } + "</ol>")
        val recalled: dynamic = breadcrumbIndexRecall
        html("#breadcrumbIndexRecall", if (recalled == null) "" else
            "<div class=\"detail-meta\"><strong>${esc(recalled.title)}</strong><span class=\"mono\">${esc(recalled.ref)}</span>" +
                "<span>${count(recalled.selectedChars)} selected / ${count(recalled.omittedChars)} omitted characters</span>" +
                "<span>${esc(recalled.rangeUnit)}</span><span>Budget ${count(recalled.budgetChars)}</span></div>" +
                "<p class=\"reel-measure-note\">${list(recalled.provenance).joinToString(" · ") { esc(it.source) }}</p>" +
                list(recalled.ranges).joinToString("") { range ->
                    "<div class=\"breadcrumb-range\"><b>[${esc(range.start)}, ${esc(range.end)})</b>\n${esc(range.content)}</div>"
                })
    }

    suspend fun searchBreadcrumbIndex() {
        val context = selectedContext() ?: return toast("Select a session or context first")
        if (breadcrumbIndexBusy) return
        breadcrumbIndexBusy = true
        try {
            val query = (qq("#breadcrumbIndexQuery") as HTMLInputElement).value
            val index = request("/v1/breadcrumbs?context=" + reelUrl(context) + "&view=index&q=" + reelUrl(query))
            if (selectedContext() == context) {
                breadcrumbIndex = index; breadcrumbIndexContext = context; breadcrumbIndexRecall = null
                renderBreadcrumbIndex()
            }
        } catch (failure: Throwable) { toast(failure.message ?: "Breadcrumb search failed") }
        finally { breadcrumbIndexBusy = false }
    }

    fun reelVoteEligible(run: dynamic, winner: String): Boolean {
        if (run == null || strOrEmpty(run.status) !in setOf("completed", "partial", "interrupted", "failed")) return false
        val completed = list(run.lanes).filter {
            strOrEmpty(it.id) in setOf("full", "main", "micro") && it.status == "completed" &&
                strOrEmpty(it.output).isNotBlank() && strOrEmpty(it.resultId).isNotBlank()
        }.map { str(it.id) }.toSet()
        return if (winner == "tie") completed.size >= 2 else winner in completed
    }

    fun renderReel() {
        val config: dynamic = reel?.defaults
        val run: dynamic = reel?.run
        qq("#reelLive").textContent = if (live) "Pause updates" else "Resume updates"
        qq("#reelLive").setAttribute("aria-pressed", live.toString())
        val modelSelect = qq("#reelMainModel") as HTMLSelectElement
        val chosen = modelSelect.value
        val configured = strOrEmpty(config?.mainModel)
        html(modelSelect, "<option value=\"\">${esc(if (configured.isEmpty()) "Configured main model" else configured + " · configured")}</option>" +
            list(models).joinToString("") { "<option value=\"${esc(it.model)}\">${esc(it.model)}</option>" })
        if (list(models).any { it.model == chosen }) modelSelect.value = chosen
        qq("#reelLocalModel").textContent = if (config == null) "Waiting for configuration" else strOrEmpty(config.heuristic?.generator)
        qq("#reelLocalModel").title = "No model call: deterministic lexical selection of whole source lines, with optional System1 review"
        if (!reelDefaults && config != null) {
            val input = qq("#reelSources") as HTMLTextAreaElement
            if (input.value.isBlank()) input.value = list(config.sources).joinToString("\n") { str(it) }
            reelDefaults = true
        }
        val history = qq("#reelHistory") as HTMLSelectElement
        html(history, "<option value=\"\">Latest run</option>" + list(reel?.history).joinToString("") {
            "<option value=\"${esc(it.id)}\">${esc(date(it.startedAt))} · ${esc(it.id)} · ${esc(it.status)}</option>"
        })
        history.value = reelHistoryId ?: ""
        val agenda = qq("#reelAgenda") as HTMLSelectElement
        val prior = agenda.value
        html(agenda, "<option value=\"\">No prior summary</option>" + list(reel?.selections).joinToString("") {
            "<option value=\"${esc(it.attemptId)}\">${esc(it.runId)} / ${esc(it.lane)} · ${esc(it.concept)} · ${esc(it.correctness)}</option>"
        })
        if (list(reel?.selections).any { it.attemptId == prior }) agenda.value = prior
        val running = truthy(reel?.activeRunId)
        (qq("#reelRun") as HTMLButtonElement).disabled = reelSubmitting || running || config == null
        html("#reelStatus", if (run == null) "<span>No archived comparison.</span>" else
            badge(run.status) + "<span class=\"mono\">${esc(run.id)}</span><span>${esc(date(run.startedAt))}</span>" +
                "<span>${count(run.sourceChars)} source characters</span>" +
                (if (run.endedAt == null) "" else "<span>${MuxCore.duration(num(run.endedAt) - num(run.startedAt))} elapsed</span>") +
                (if (truthy(run.error)) "<span class=\"reel-error\">${esc(run.error)}</span>" else ""))
        val capture = run?.capture
        qq("#reelCapture").textContent = if (capture == null) "No archive manifest is available." else
            "${count(capture.watermark)} captured payloads · ${count(capture.capturedBytes)} bytes · " +
                "${count(capture.missingPayloads)} missing · durable CAS: ${str(capture.durable)}. ${strOrEmpty(capture.scope)}"
        val retainFrom = qq("#reelRetainFrom") as HTMLInputElement
        val retainUntil = qq("#reelRetainUntil") as HTMLInputElement
        fun localDate(ms: Double): String {
            val value = Date(ms)
            return Date(ms - value.getTimezoneOffset() * 60000).toISOString().take(16)
        }
        if (capture != null && capture.fromMs != null && retainFrom.value.isBlank()) {
            retainFrom.value = localDate(num(capture.fromMs))
            retainUntil.value = localDate(num(capture.throughMs) + 60000)
        }
        html("#reelPins", list(run?.retention).joinToString("<br>") {
            "${esc(date(it.fromMs))} – ${esc(date(it.untilMs))} · ${count(it.pinnedBytes)} / ${count(it.populationBytes)} captured bytes pinned · watermark ${count(it.watermark)}"
        })
        html("#reelPayloads", list(capture?.records).joinToString("") {
            "<button data-action=\"reel-payload\" data-cid=\"${esc(it.cid)}\">${esc(it.kind)} · ${esc(it.name)} · ${count(it.bytes)} bytes</button> "
        })
        (qq("#reelReviewSave") as HTMLButtonElement).disabled = run == null || list(run.lanes).none { truthy(it.resultId) }
        html("#reelReviews", list(run?.reviews).joinToString("") {
            "<p>${esc(time(it.atMs))} · ${esc(it.lane)} · ${esc(it.correctness)} · ${esc(it.disposition)}" +
                (if (truthy(it.concept)) " · <strong>${esc(it.concept)}</strong>" else "") +
                (if (truthy(it.note)) " · ${esc(it.note)}" else "") +
                (if (truthy(it.correctionCid)) " · <button data-action=\"reel-payload\" data-cid=\"${esc(it.correctionCid)}\">Human correction</button>" else "") + "</p>"
        })
        val lanes = list(run?.lanes)
        html("#reelLanes", if (lanes.isEmpty()) empty(
            if (errors.containsKey("Evolution reel")) "Comparison service unavailable" else "Run the current source to collect actual model outputs", "git-branch"
        ) else lanes.joinToString("") { lane ->
            val model: dynamic = if (lane.id == "micro") reel?.run?.models?.micro else reel?.run?.models?.main
            "<article class=\"reel-lane\" data-lane=\"${esc(lane.id)}\"><header><h3>${esc(lane.title)}</h3>${badge(lane.status)}</header>" +
                "<span class=\"reel-model mono\">${esc(model)}</span>" +
                (if (truthy(lane.error)) "<p class=\"reel-error\">${esc(lane.error)}</p>" else "") +
                (if (truthy(lane.output)) "<div class=\"reel-output\">${esc(lane.output)}</div>" else
                    empty(if (isActive(lane.status)) "Collecting evidence and summarizing" else "No output recorded", "file-text")) + "</article>"
        })
        val limits = run?.outputLimits
        val reasoning = if (run?.mainRequestOptions?.chat_template_kwargs?.enable_thinking == false)
            " Main model reasoning is disabled for summaries and assessment." else ""
        val allowance = if (limits == null) "" else
            "<p class=\"reel-measure-note\">${esc(run.harness)} · output-token limits: main ${count(limits.main)}, assessment ${count(limits.assessment)}; heuristic lane: no tokens, ${count(limits.microProjectionChars)} characters. Reasoning, when enabled, uses the same output allowance.$reasoning</p>"
        html("#reelMeasurements", if (lanes.isEmpty()) "" else
            "<div class=\"table-wrap reel-meters\"><table><thead><tr><th>Measured work</th><th>Calls / cached</th><th>Input tokens</th><th>Output tokens</th><th>Elapsed</th><th>Summary characters</th><th>Summary / source</th></tr></thead><tbody>" +
                lanes.joinToString("") { lane ->
                    val source = num(lane.sourceChars)
                    val retained = if (source > 0 && lane.summaryChars != null)
                        str((num(lane.summaryChars) / source * 100).asDynamic().toFixed(1)) + "%" else "--"
                    "<tr><td>${esc(lane.title)}</td><td>${count(lane.calls)} / ${count(lane.cachedCalls)}</td><td>${count(lane.inputTokens)}</td><td>${count(lane.outputTokens)}</td>" +
                        "<td>${if (lane.latencyMs == null) "--" else MuxCore.duration(num(lane.latencyMs))}</td>" +
                        "<td>${count(lane.summaryChars)}</td><td>${esc(retained)}</td></tr>"
                } + "</tbody></table></div>" + allowance + "<p class=\"reel-measure-note\">Cached calls add no provider tokens; their elapsed time is retrieval, not an inference-speed comparison. The character ratio measures compression, independently of complete source retention or factuality.</p>")
        val assessment = run?.assessment
        qq("#reelAssessment").hidden = assessment == null
        if (assessment != null) {
            html("#reelAssessmentStatus", badge(assessment.status))
            qq("#reelAssessmentQualification").textContent = strOrEmpty(assessment.qualification)
            html("#reelAssessmentLabels", "<span class=\"mono\">${esc(assessment.model)}</span>" +
                listOf("A", "B", "C").joinToString("") { label ->
                    if (assessment.labels == null || assessment.labels[label] == null) "" else "<span>$label = ${esc(assessment.labels[label])}</span>"
                })
            qq("#reelAssessmentContent").textContent = when {
                truthy(assessment.content) -> str(assessment.content)
                truthy(assessment.error) -> str(assessment.error)
                assessment.status == "running" -> "Comparing anonymous outputs with the retained source."
                else -> "No assessment recorded."
            }
        }
        val voteButtons = document.querySelectorAll("#reelVoteButtons button")
        for (i in 0 until voteButtons.length) {
            val button = voteButtons.item(i) as HTMLButtonElement
            button.disabled = reelVoteSubmitting || !reelVoteEligible(run, button.getAttribute("data-winner") ?: "")
        }
        html("#reelVotes", list(run?.votes).joinToString("<br>") {
            "${esc(time(it.atMs))} · ${esc(it.winner)}${if (truthy(it.note)) " · " + esc(it.note) else ""}"
        })
        val events = list(run?.events)
        html("#reelEvents", if (events.isEmpty()) "<li class=\"muted\">No observed events yet</li>" else events.joinToString("") { event ->
            val parents = list(event.parentIds)
            "<li class=\"reel-event\"><div class=\"reel-event-header\"><time>${esc(time(event.atMs))}</time>" +
                badge("", strOrEmpty(event.kind).replace('_', ' ')) +
                (if (truthy(event.lane)) badge("", event.lane) else "") + "<strong>${esc(event.title)}</strong></div>" +
                (if (truthy(event.detail)) "<p>${esc(event.detail)}</p>" else "") +
                (if (truthy(event.breadcrumbId)) "<p class=\"reel-lineage\">" +
                    (if (parents.isEmpty()) "Source → " else parents.joinToString(" + ") { esc(it) } + " → ") + esc(event.breadcrumbId) + "</p>" else "") +
                "<div class=\"detail-meta\">" +
                (if (truthy(event.model)) "<span class=\"mono\">${esc(event.model)}</span>" else "") +
                (if (truthy(event.contextId)) "<a href=\"/modelmux?context=${reelUrl(event.contextId)}\" target=\"_blank\" rel=\"noopener\">Context ${esc(event.contextId)}</a>" else "") +
                (if (truthy(event.boardKey)) "<button data-action=\"reel-evidence\" data-key=\"${esc(event.boardKey)}\">${icon("table")}Inspect Cursor evidence</button>" else "") +
                "</div></li>"
        })
    }

    suspend fun reelEvidence(key: String) {
        try {
            val family = request("/blackboard/sheet?key=" + reelUrl(key))
            reelSheets = family
            reelBoardKey = key
            reelSheetId = list(family).firstOrNull()?.id as String?
            (qq("#reelSheetFilter") as HTMLInputElement).value = ""
            qq("#reelSheet").hidden = false
            renderReelSheet()
            icons()
            qq("#reelSheet").scrollIntoView()
        } catch (failure: Throwable) { toast(failure.message ?: "Evidence unavailable") }
    }

    fun renderReelSheet() {
        val sheet = list(reelSheets).firstOrNull { it.id == reelSheetId } ?: return
        qq("#reelSheetTitle").textContent = strOrEmpty(sheet.title)
        val columns = list(sheet.columns)
        val select = qq("#reelSheetColumn") as HTMLSelectElement
        val prior = select.value
        html(select, "<option value=\"\">All columns</option>" + columns.mapIndexed { i, column ->
            "<option value=\"$i\">${esc(column.name)}</option>"
        }.joinToString(""))
        if (prior.toIntOrNull()?.let { it in columns.indices } == true) select.value = prior
        val facet = select.value.toIntOrNull()
        val filter = (qq("#reelSheetFilter") as HTMLInputElement).value.lowercase()
        val rows = list(sheet.rows).filter { row ->
            val cells = list(row)
            val values = if (facet != null) listOfNotNull(cells.getOrNull(facet)) else cells
            values.any { str(if (it != null && jsTypeOf(it) == "object") JSON.stringify(it) else it).lowercase().contains(filter) }
        }
        html("#reelSheetNav",
            (if (truthy(sheet.parent)) "<button data-action=\"reel-sheet\" data-sheet=\"${esc(sheet.parent)}\">${icon("corner-left-up")}Parent</button>" else "") +
                "<span class=\"mono\">${esc(sheet.id)}</span><span>${rows.size} / ${list(sheet.rows).size} rows</span>" +
                (if (truthy(sheet.truncated)) badge("queued", "Limited: " + strOrEmpty(sheet.limit)) else "") +
                "<a href=\"/blackboard/sheet?key=${reelUrl(reelBoardKey)}\" target=\"_blank\" rel=\"noopener\">Source JSON ${icon("arrow-up-right")}</a>")
        html("#reelSheetRows", "<table><thead><tr>" + columns.joinToString("") {
            "<th>${esc(it.name)}<small>${esc(it.type)}</small></th>"
        } + "</tr></thead><tbody>" + rows.joinToString("") { row ->
            "<tr>" + list(row).joinToString("") { cell ->
                "<td>" + (if (cell != null && truthy(cell.sheet)) "<button data-action=\"reel-sheet\" data-sheet=\"${esc(cell.sheet)}\">${icon("table")}${esc(cell.sheet)}</button>"
                else esc(if (cell != null && jsTypeOf(cell) == "object") JSON.stringify(cell) else cell)) + "</td>"
            } + "</tr>"
        } + "</tbody></table>")
    }

    private fun render() {
        showErrors(); metrics(); renderRecent()
        if (page == "keys") renderKeys()
        if (page == "models") { renderModelRows(); renderReel(); html("#modelActivity", attemptsTable(list(calls).takeLast(6))) }
        if (page != "stats") { renderBreadcrumbs(); renderBreadcrumbIndex() }
        if (page == "sessions") renderSessions()
        if (page == "stats") renderStats()
        icons()
    }

    private suspend fun mutate(action: suspend () -> Unit) {
        if (mutating) return
        mutating = true
        try { action(); errors.remove("Action"); poll(true) } catch (e: Throwable) { errors["Action"] = e.message ?: ""; showErrors() } finally { mutating = false }
    }

    private fun goSession(id: dynamic) { window.location.href = "/mux/sessions?id=" + str(id) }

    private fun exportJson(value: dynamic, name: String) {
        val bag: dynamic = obj(); bag.type = "application/json"
        val url = URL.createObjectURL(Blob(arrayOf(JSON.stringify(value, null, 2)), bag.unsafeCast<BlobPropertyBag>()))
        val link = document.createElement("a") as HTMLAnchorElement
        link.href = url; link.download = name; link.click()
        setTimeout(5000) { URL.revokeObjectURL(url) }
    }

    private fun openEndpoint(entry: dynamic) {
        val form = qq("#endpointForm") as HTMLFormElement
        form.reset()
        val el: dynamic = form.asDynamic().elements
        for (key in listOf("name", "base", "model", "envVar")) el[key].value = if (entry != null && truthy(entry[key])) entry[key] else ""
        el.auth.value = if (entry?.auth == "none") "none" else "required"
        el.envVar.required = el.auth.value == "required"
        el.envVar.disabled = el.auth.value == "none"
        el.name.readOnly = entry != null
        el.enabled.checked = entry?.flags?.enabled != false
        el.preferred.checked = truthy(entry?.flags?.preferred)
        qq("#endpointError").textContent = ""
        qq("#endpointTitle").textContent = if (entry != null) "Edit endpoint" else "Add endpoint"
        (qq("#endpointDialog") as HTMLDialogElement).showModal()
    }

    private suspend fun sendFollowup(event: Event) {
        event.preventDefault()
        val button = (event.currentTarget as Element).querySelector("button[type=submit]") as HTMLButtonElement
        val prompt = (qq("#followupPrompt") as HTMLTextAreaElement).value
        val model = (qq("#followupModel") as HTMLSelectElement).value
        button.disabled = true
        mutate {
            val b: dynamic = obj()
            b.id = selected; b.prompt = prompt; b.models = if (model.isNotEmpty()) arrayOf(model) else emptyArray<String>()
            b.mode = "chat"; b.maxTokens = 1024; b.temperature = .2
            request("/api/mux/sessions/run", b)
            (qq("#followupPrompt") as HTMLTextAreaElement).value = ""
        }
        button.disabled = isActive(detail?.status)
    }

    private fun onClick(event: Event) {
        val b = (event.target as? Element)?.closest("[data-action]") as HTMLElement? ?: return
        val a = b.getAttribute("data-action")
        when (a) {
            "breadcrumb-index-recall" -> {
                val context = selectedContext() ?: return
                val ref = b.getAttribute("data-ref") ?: return
                val cid = b.getAttribute("data-cid") ?: return
                if (context != breadcrumbIndexContext || breadcrumbIndexBusy) return
                val presence = (qq("#breadcrumbIndexPresence") as HTMLInputElement).value.toDoubleOrNull()
                if (presence == null || !presence.isFinite() || presence !in 0.0..1.0) { toast("Recall presence must be from 0 to 1"); return }
                breadcrumbIndexBusy = true
                launchPage {
                    try {
                        val payload: dynamic = obj()
                        payload.context = context; payload.tool = "recall"; payload.ref = ref; payload.cid = cid
                        payload.presence = presence; payload.query = (qq("#breadcrumbIndexQuery") as HTMLInputElement).value
                        val recalled = request("/v1/breadcrumbs", payload)
                        if (truthy(recalled.error)) throw Exception(str(recalled.error))
                        if (selectedContext() == context) { breadcrumbIndexRecall = recalled; renderBreadcrumbIndex() }
                        poll(true)
                    } catch (failure: Throwable) { toast(failure.message ?: "Breadcrumb recall failed") }
                    finally { breadcrumbIndexBusy = false }
                }
                return
            }
            "reel-live" -> { qq("#liveToggle").click(); renderReel(); return }
            "reel-payload" -> {
                val run = reel?.run ?: return
                val cid = b.getAttribute("data-cid") ?: return
                launchPage {
                    try {
                        val payload = request("/api/mux/reel/payload?runId=" + reelUrl(run.id) + "&cid=" + reelUrl(cid))
                        qq("#reelPayload").hidden = false
                        qq("#reelPayload").textContent = strOrEmpty(payload.content)
                        qq("#reelPayload").scrollIntoView()
                    } catch (failure: Throwable) { toast(failure.message ?: "Payload unavailable") }
                }
                return
            }
            "reel-evidence" -> { b.getAttribute("data-key")?.let { key -> launchPage { reelEvidence(key) } }; return }
            "reel-sheet-close" -> { qq("#reelSheet").hidden = true; return }
            "reel-sheet" -> {
                val id = b.getAttribute("data-sheet") ?: return
                if (list(reelSheets).any { it.id == id }) {
                    reelSheetId = id
                    (qq("#reelSheetFilter") as HTMLInputElement).value = ""
                    (qq("#reelSheetColumn") as HTMLSelectElement).value = ""
                    renderReelSheet(); icons()
                } else toast("Child sheet is outside this bounded projection; inspect the source JSON")
                return
            }
            "reel-vote" -> {
                val run = reel?.run ?: return
                val winner = b.getAttribute("data-winner") ?: return
                if (reelVoteSubmitting || !reelVoteEligible(run, winner)) return
                reelVoteSubmitting = true; renderReel()
                launchPage {
                    try {
                        val payload: dynamic = obj()
                        payload.runId = run.id; payload.winner = winner
                        payload.note = (qq("#reelVoteNote") as HTMLInputElement).value
                        request("/api/mux/reel/vote", payload)
                        (qq("#reelVoteNote") as HTMLInputElement).value = ""
                        toast("Quality vote recorded"); poll(true)
                    } catch (failure: Throwable) { toast(failure.message ?: "Vote failed") }
                    finally { reelVoteSubmitting = false; renderReel() }
                }
                return
            }
            "refresh" -> { launchPage { poll(true) }; return }
            "close-dialog" -> { (qq("#endpointDialog") as HTMLDialogElement).close(); return }
            "close-rename" -> { (qq("#renameDialog") as HTMLDialogElement).close(); return }
            "add-endpoint" -> { openEndpoint(null); return }
            "edit-endpoint" -> { openEndpoint(list(endpoints)[(js("Number")(b.getAttribute("data-index")) as Double).toInt()]); return }
            "select-session" -> {
                selected = js("Number")(b.getAttribute("data-id")) as Double
                window.history.replaceState(null, "", "?id=" + str(selected))
                detail = null; detailShell = null; breadcrumbSubmission = null; breadcrumbCalls = null
                errors.remove("Breadcrumbs"); errors.remove("Context session"); errors.remove("Context routing")
                renderSessions(); renderBreadcrumbs(); launchPage { poll(true) }; return
            }
            "session-tab" -> { tab = b.getAttribute("data-tab") ?: tab; renderSessions(); icons(); return }
            "deselect-model" -> { modelsSelected.remove(b.getAttribute("data-model")); renderModelRows(); icons(); return }
            "export-activity" -> { val v: dynamic = obj(); v.atMs = nowMs(); v.calls = calls; v.standings = standings; exportJson(v, "mux-activity.json"); return }
            "refresh-discovery" -> {
                launchPage {
                    try { keyedModels = request("/api/mux/discovery?refresh=1"); errors.remove("Keyed models") }
                    catch (e: Throwable) { errors["Keyed models"] = e.message ?: "" }
                    renderKeys(); icons()
                }
                return
            }
            "export-session" -> { exportJson(detail, "session-" + str(selected) + ".json"); return }
            "rename" -> {
                (qq("#renameForm") as HTMLFormElement).asDynamic().elements.title.value = detail.title
                (qq("#renameDialog") as HTMLDialogElement).showModal(); return
            }
        }
        val index = b.getAttribute("data-index")
        launchPage {
            mutate {
                if (a == "new") { val t: dynamic = obj(); t.title = "New session"; goSession(request("/api/mux/sessions", t).id) }
                if (a == "fork") { val t: dynamic = obj(); t.id = selected; goSession(request("/api/mux/sessions/fork", t).id) }
                if (a == "archive") {
                    val t: dynamic = obj(); t.id = selected; t.archived = !truthy(detail.archived)
                    request("/api/mux/sessions/update", t)
                    toast(if (truthy(detail.archived)) "Session restored" else "Session archived")
                }
                if (a == "cancel") { val t: dynamic = obj(); t.id = selected; request("/api/mux/sessions/cancel", t); toast("Stopping run and branches") }
                if (a == "delete-endpoint") {
                    val entry = list(endpoints)[(js("Number")(index) as Double).toInt()]
                    request("/api/mux/endpoints/" + (js("encodeURIComponent")(entry.name) as String), undefined, "DELETE")
                    toast("Endpoint removed: " + str(entry.name))
                }
            }
        }
    }

    fun mount() {
        if (URLSearchParams(window.location.search).get("widget") == "1") document.body?.classList?.add("reel-widget")
        document.addEventListener("click", { onClick(it) })
        (q("#breadcrumbIndexForm") as HTMLFormElement?)?.onsubmit = { event: Event ->
            event.preventDefault(); launchPage { searchBreadcrumbIndex() }; null
        }
        q("#reelSheetFilter")?.oninput = { renderReelSheet(); icons() }
        q("#reelSheetColumn")?.onchange = { renderReelSheet(); icons() }
        q("#reelHistory")?.onchange = {
            reelHistoryId = (qq("#reelHistory") as HTMLSelectElement).value.takeIf { it.isNotBlank() }
            qq("#reelPayload").hidden = true
            launchPage { poll(true) }
        }
        (q("#reelRetention") as HTMLFormElement?)?.onsubmit = { event: Event ->
            event.preventDefault()
            launchPage {
                try {
                    val payload: dynamic = obj()
                    payload.fromMs = Date((qq("#reelRetainFrom") as HTMLInputElement).value).getTime()
                    payload.untilMs = Date((qq("#reelRetainUntil") as HTMLInputElement).value).getTime()
                    payload.fraction = ((qq("#reelRetainPercent") as HTMLInputElement).value.toDoubleOrNull() ?: 100.0) / 100
                    request("/api/mux/reel/retain", payload)
                    toast("Captured-payload retention pinned"); poll(true)
                } catch (failure: Throwable) { toast(failure.message ?: "Retention failed") }
            }
            null
        }
        (q("#reelReview") as HTMLFormElement?)?.onsubmit = { event: Event ->
            event.preventDefault()
            val run = reel?.run
            if (run != null) launchPage {
                try {
                    val payload: dynamic = obj()
                    payload.runId = run.id
                    payload.lane = (qq("#reelReviewLane") as HTMLSelectElement).value
                    payload.correctness = (qq("#reelCorrectness") as HTMLSelectElement).value
                    payload.disposition = (qq("#reelDisposition") as HTMLSelectElement).value
                    payload.concept = (qq("#reelConcept") as HTMLInputElement).value
                    payload.note = (qq("#reelReviewNote") as HTMLInputElement).value
                    payload.correction = (qq("#reelCorrection") as HTMLTextAreaElement).value
                    request("/api/mux/reel/review", payload)
                    toast("Human review saved"); poll(true)
                } catch (failure: Throwable) { toast(failure.message ?: "Review failed") }
            }
            null
        }
        (q("#reelForm") as HTMLFormElement?)?.onsubmit = { event: Event ->
            event.preventDefault()
            if (!reelSubmitting) {
                reelSubmitting = true; renderReel()
                launchPage {
                    try {
                        val payload: dynamic = obj()
                        val model = (qq("#reelMainModel") as HTMLSelectElement).value
                        if (model.isNotBlank()) payload.mainModel = model
                        val paths = (qq("#reelSources") as HTMLTextAreaElement).value.lines().map { it.trim() }.filter { it.isNotEmpty() }
                        if (paths.isNotEmpty()) payload.sources = paths.toTypedArray()
                        val agenda = (qq("#reelAgenda") as HTMLSelectElement).value
                        if (agenda.isNotBlank()) payload.selectionIds = arrayOf(agenda)
                        request("/api/mux/reel/run", payload)
                        reelHistoryId = null
                        toast("Comparison started"); poll(true)
                    } catch (failure: Throwable) { toast(failure.message ?: "Comparison failed") }
                    finally { reelSubmitting = false; renderReel() }
                }
            }
            null
        }
        document.addEventListener("change", { event: Event ->
            val target = event.target as? HTMLInputElement
            if (target != null && target.matches("[data-model]")) {
                val model = target.getAttribute("data-model") ?: ""
                if (target.checked) {
                    if ((qq("#runMode") as HTMLSelectElement).value == "chat") modelsSelected.clear()
                    if (modelsSelected.size >= 6) { target.checked = false; toast("Up to six fan-out models"); return@addEventListener }
                    modelsSelected.add(model)
                } else modelsSelected.remove(model)
                renderModelRows(); icons()
            } else if (target != null && target.matches("[data-recall]")) {
                recallDrag = false
                val context = selectedContext() ?: return@addEventListener
                launchPage { recall(context, target.getAttribute("data-recall") ?: "", js("Number")(target.value) as Double) }
            }
        })
        document.addEventListener("input", { event: Event ->
            val target = event.target as? HTMLInputElement ?: return@addEventListener
            val id = target.getAttribute("data-recall") ?: return@addEventListener
            val context = selectedContext() ?: return@addEventListener
            val presence = js("Number")(target.value) as Double
            recallDrag = true
            recallPresence[context + "\u0000" + id] = presence
            (target.nextElementSibling as? HTMLElement)?.textContent = presence.asDynamic().toFixed(2) as String
        })
        qq("#liveToggle").onclick = {
            live = !live
            val b = qq("#liveToggle")
            b.setAttribute("aria-pressed", live.toString())
            b.title = if (live) "Pause live updates" else "Resume live updates"
            b.setAttribute("aria-label", b.title)
            html(b, icon(if (live) "pause" else "play"))
            showErrors(); icons()
            if (live) launchPage { poll(true) }
        }
        for (id in listOf("keySearch", "modelSearch", "sessionSearch")) q("#$id")?.oninput = { render() }
        for (id in listOf("keyStatus", "sessionStatus", "statsWindow", "attemptStatus")) q("#$id")?.onchange = { render() }
        q("#runMode")?.onchange = {
            if ((qq("#runMode") as HTMLSelectElement).value == "chat" && modelsSelected.size > 1) modelsSelected = linkedSetOf(modelsSelected.first())
            renderModelRows(); icons()
        }
        q("#maxTokens")?.oninput = { selection() }
        q("#runSession")?.onchange = {
            breadcrumbContext = (qq("#runSession") as HTMLSelectElement).value.takeIf { it.isNotEmpty() }
            breadcrumbSubmission = null; breadcrumbCalls = null
            errors.remove("Breadcrumbs")
            errors.remove("Context session")
            errors.remove("Context routing")
            renderBreadcrumbs()
            launchPage { poll(true) }
        }
        q("#breadcrumbContext")?.onchange = {
            breadcrumbContext = (qq("#breadcrumbContext") as HTMLSelectElement).value.takeIf { it.isNotEmpty() }
            if (page == "models" && breadcrumbContext != null) {
                val runSession = qq("#runSession") as HTMLSelectElement
                val options = runSession.options
                if ((0 until options.length).any { options.item(it).asDynamic().value == breadcrumbContext })
                    runSession.value = breadcrumbContext!!
            }
            breadcrumbSubmission = null; breadcrumbCalls = null
            errors.remove("Breadcrumbs")
            errors.remove("Context session")
            errors.remove("Context routing")
            renderBreadcrumbs()
            launchPage { poll(true) }
        }
        (q("#runForm") as HTMLFormElement?)?.onsubmit = { event: Event ->
            event.preventDefault()
            val ms = modelsSelected.toList()
            val mode = (qq("#runMode") as HTMLSelectElement).value
            if (mode != "chat" && ms.size < 2) toast("Select at least two models from the roster")
            else {
                val button = (event.currentTarget as Element).querySelector("button[type=submit]") as HTMLButtonElement
                button.disabled = true
                launchPage {
                    mutate {
                        val chosen = js("Number")((qq("#runSession") as HTMLSelectElement).value) as Double
                        val id: dynamic = if (chosen != 0.0 && !chosen.isNaN()) chosen else { val t: dynamic = obj(); t.title = "New session"; request("/api/mux/sessions", t).id }
                        val b: dynamic = obj()
                        b.id = id; b.models = ms.toTypedArray(); b.mode = mode; b.prompt = (qq("#runPrompt") as HTMLTextAreaElement).value
                        b.maxTokens = js("Number")((qq("#maxTokens") as HTMLInputElement).value); b.temperature = js("Number")((qq("#temperature") as HTMLInputElement).value)
                        request("/api/mux/sessions/run", b)
                        goSession(id)
                    }
                    button.disabled = false
                }
            }
            null
        }
        (qq("#endpointForm") as HTMLFormElement).onsubmit = { event: Event ->
            event.preventDefault()
            val form = event.currentTarget as HTMLFormElement
            val button = form.querySelector("button[type=submit]") as HTMLButtonElement
            button.disabled = true
            launchPage {
                try {
                    val el: dynamic = form.asDynamic().elements
                    val payload: dynamic = obj()
                    for (k in listOf("name", "base", "model", "envVar")) payload[k] = (el[k].value as String).trim()
                    payload.auth = el.auth.value
                    if (payload.auth == "none") payload.envVar = ""
                    val flags: dynamic = obj(); flags.enabled = el.enabled.checked; flags.preferred = el.preferred.checked
                    payload.flags = flags
                    request("/api/mux/endpoints", payload)
                    (qq("#endpointDialog") as HTMLDialogElement).close(); toast("Endpoint registry saved"); poll(true)
                } catch (e: Throwable) {
                    qq("#endpointError").textContent = e.message ?: ""
                } finally {
                    button.disabled = false
                }
            }
            null
        }
        (qq("#endpointForm") as HTMLFormElement).onchange = {
            val el: dynamic = (qq("#endpointForm") as HTMLFormElement).asDynamic().elements
            el.envVar.required = el.auth.value == "required"
            el.envVar.disabled = el.auth.value == "none"
        }
        (qq("#renameForm") as HTMLFormElement).onsubmit = { event: Event ->
            event.preventDefault()
            val title = (event.target as HTMLFormElement).asDynamic().elements.title.value
            launchPage {
                mutate {
                    val b: dynamic = obj(); b.id = selected; b.title = title
                    request("/api/mux/sessions/update", b)
                    (qq("#renameDialog") as HTMLDialogElement).close()
                }
            }
            null
        }
        document.addEventListener("keydown", { e: Event ->
            val event = e as KeyboardEvent
            val target = event.target as? Element
            if (target != null && target.matches(".session-tabs button") && (event.key == "ArrowLeft" || event.key == "ArrowRight")) {
                event.preventDefault()
                val nodes = document.querySelectorAll(".session-tabs button")
                val tabs = (0 until nodes.length).map { nodes.item(it) as HTMLElement }
                val index = tabs.indexOf(target)
                val next = tabs[(index + (if (event.key == "ArrowRight") 1 else tabs.size - 1)) % tabs.size]
                next.click(); next.focus()
            }
        })
        document.addEventListener("visibilitychange", { if (document.asDynamic().hidden != true && live) launchPage { poll(true) } })
        window.addEventListener("resize", { if (page == "stats") renderStats() })
        val (title, eyebrow) = titles.getValue(page)
        document.title = "$title | Forge"
        qq("#pageTitle").textContent = title
        qq("#eyebrow").textContent = eyebrow
        qq("#breadcrumb").textContent = "Workspace / $title"
        qq("#${page}View").hidden = false
        qq("#breadcrumbsView").hidden = page == "stats"
        qq("#breadcrumbSelection").hidden = page == "sessions"
        val navs = document.querySelectorAll("[data-page=\"$page\"]")
        for (i in 0 until navs.length) (navs.item(i) as Element).setAttribute("aria-current", "page")
        html("#pageActions", if (page == "keys") "<a href=\"/mux/stats\">Quota activity " + icon("arrow-up-right") + "</a>"
        else if (page == "stats") btn("export-activity", "Export activity", "download")
        else "<button class=\"primary\" data-action=\"new\">" + icon("plus") + "New session</button>")
        selected = URLSearchParams(window.location.search).get("id")?.let { (js("Number")(it) as Double).takeIf { n -> n != 0.0 && !n.isNaN() } }
        breadcrumbContext = URLSearchParams(window.location.search).get("context")?.takeIf { it.isNotBlank() }
        render()
        launchPage { poll(true) }
        fun loop() { launchPage { poll(); setTimeout(2000) { loop() } } }
        setTimeout(2000) { loop() }
    }
}
