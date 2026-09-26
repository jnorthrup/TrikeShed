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

    private fun q(s: String): HTMLElement? = document.querySelector(s) as HTMLElement?
    private fun qq(s: String): HTMLElement = document.querySelector(s) as HTMLElement
    private fun rosterRows(): dynamic = catalogRoster ?: roster
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
        val sources = ArrayList<Triple<String, String, (dynamic) -> Unit>>()
        sources.add(Triple("Activity", "/api/mux/activity") { r -> calls = if (truthy(r.calls)) r.calls else js("[]") })
        sources.add(Triple("Sessions", "/api/mux/sessions") { r -> sessions = if (truthy(r.sessions)) r.sessions else js("[]") })
        sources.add(Triple("Quota", "/api/mux/standings") { r -> standings = if (truthy(r.standings)) r.standings else js("[]"); quotaAt = r.atMs })
        if (catalog) {
            sources.add(Triple("Credentials", "/api/mux/keys") { r -> roster = if (truthy(r.roster)) r.roster else js("[]") })
            sources.add(Triple("Models", "/api/mux/catalog") { r -> models = if (truthy(r.models)) r.models else js("[]"); catalogRoster = if (truthy(r.roster)) r.roster else null })
            sources.add(Triple("Endpoints", "/api/mux/endpoints") { r -> endpoints = if (truthy(r.user)) r.user else js("[]") })
        }
        val jobs = sources.map { (name, path, apply) ->
            pageScope.async {
                try { apply(request(path)); errors.remove(name) } catch (e: Throwable) { errors[name] = e.message ?: "" }
            }
        }
        jobs.forEach { it.await() }
        if (catalog) lastCatalog = nowMs()
        if (page == "sessions" && selected == null) selected = list(sessions).firstOrNull { !truthy(it.archived) }.let { f -> if (f == null) null else num(f.id) }
        val id = selected
        if (page == "sessions" && id != null) {
            try {
                val d = request("/api/mux/sessions?id=" + str(id))
                if (selected == id) { detail = d; errors.remove("Session") }
            } catch (e: Throwable) { errors["Session"] = e.message ?: "" }
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
        val ready = if (ros != null) list(ros).filter { truthy(it.keyPresent) }.map { str(if (truthy(it.envVar)) it.envVar else it.name) }.toSet().size else null
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

    private fun renderKeys() {
        val search = (qq("#keySearch") as HTMLInputElement).value.lowercase()
        val status = (qq("#keyStatus") as HTMLSelectElement).value
        val rows = list(rosterRows()).filter {
            listOf(strOrEmpty(it.name), strOrEmpty(it.model), strOrEmpty(it.envVar)).joinToString(" ").lowercase().contains(search) &&
                (status == "all" || (if (status == "ready") truthy(it.keyPresent) else !truthy(it.keyPresent)))
        }
        html("#keyRows", if (rows.isNotEmpty()) rows.joinToString("") { r ->
            val s = catalogStanding(r)
            val qv = quota(s)
            val present = truthy(r.keyPresent)
            val exhausted = s != null && !truthy(s.usable)
            "<tr><td><strong>${esc(r.name)}</strong><small class=\"mono\">${esc(r.model)}</small></td><td>" +
                badge(if (present) "good" else "bad", if (present) "Present" else "Missing") + "<small class=\"mono\">${esc(r.envVar)}</small></td><td>" +
                badge(if (exhausted) "bad" else if (present) "good" else "", if (exhausted) "Exhausted" else if (present) "Available" else "Unbound") + "</td><td>" + quotaCell(s) +
                "</td><td class=\"mono\">" + (if (qv.status == "refreshing") "Refreshing" else MuxCore.duration(qv.resetMs)) + "</td><td class=\"mono\">" + count(s?.accessCount) + "</td></tr>"
        } else "<tr><td colspan=\"6\">" + empty(if (roster != null) "No matching credentials" else "Credential roster unavailable", "key-round") + "</td></tr>")
        val eps = list(endpoints)
        html("#endpointRows", if (eps.isNotEmpty()) eps.mapIndexed { index, r ->
            val bound = list(models).any { it.base == r.base && it.model == r.model }
            val enabled = r.flags?.enabled != false
            "<tr><td><strong>${esc(r.name)}</strong><small>${esc(r.base)}</small></td><td class=\"mono\">${esc(r.model)}</td><td class=\"mono\">${esc(r.envVar)}</td><td>" +
                badge(if (enabled) "good" else "", if (enabled) "Enabled" else "Disabled") + (if (truthy(r.flags?.preferred)) " " + badge("", "Preferred") else "") + "</td><td>" +
                badge(if (bound) "good" else "queued", if (bound) "In roster" else "Registry only") + "</td><td><div class=\"toolbar\">" +
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
            val present = list(rosterRows()).any { it.model == m.model && truthy(it.keyPresent) }
            val sel = model in modelsSelected
            val t = totals(list(calls).filter { it.model == m.model })
            "<tr class=\"" + (if (sel) "selected" else "") + "\"><td class=\"check-cell\"><input type=\"checkbox\" data-model=\"${esc(model)}\" aria-label=\"Select ${esc(model)}\" " +
                (if (sel) "checked" else "") + "></td><td><strong class=\"mono\" title=\"${esc(model)}\">${esc(model)}</strong><small>${esc(m.name)}</small></td><td>" +
                badge(if (present) "good" else "", if (present) "Present" else "Unresolved") + "</td><td class=\"mono\">" + count(t.attempts) + "</td><td class=\"mono\">" + MuxCore.duration(t.medianMs) + "</td></tr>"
        } else "<tr><td colspan=\"5\">" + empty(if (models != null) "No matching models" else "Model roster unavailable") + "</td></tr>")
        val runSession = qq("#runSession") as HTMLSelectElement
        val prior = runSession.value
        html(runSession, "<option value=\"\">New session</option>" + list(sessions).filter { !truthy(it.archived) && !isActive(it.status) && !truthy(it.parentId) }
            .joinToString("") { "<option value=\"" + str(it.id) + "\">" + esc(it.title) + "</option>" })
        val opts = runSession.options
        if ((0 until opts.length).any { (opts.item(it).asDynamic().value as String) == prior }) runSession.value = prior
        selection()
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
        return "<div class=\"table-wrap\"><table><thead><tr><th>Time / session</th><th>Model / key</th><th>Status</th><th>Input</th><th>Output</th><th>Latency</th><th>Cache</th></tr></thead><tbody>" +
            cs.reversed().joinToString("") { c ->
                "<tr><td><strong class=\"mono\">" + time(c.startedAt) + "</strong><small>" +
                    (if (truthy(c.conversationId)) "<a href=\"/mux/sessions?id=" + str(c.conversationId) + "\">Session " + str(c.conversationId) + "</a>" else esc(if (truthy(c.assessmentId)) c.assessmentId else "External call")) +
                    "</small></td><td><strong class=\"mono\" title=\"${esc(c.model)}\">${esc(c.model)}</strong><small class=\"mono\">" + esc(if (truthy(c.keyId)) c.keyId else c.provider) +
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

    private fun render() {
        showErrors(); metrics(); renderRecent()
        if (page == "keys") renderKeys()
        if (page == "models") { renderModelRows(); html("#modelActivity", attemptsTable(list(calls).takeLast(6))) }
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
            "refresh" -> { launchPage { poll(true) }; return }
            "close-dialog" -> { (qq("#endpointDialog") as HTMLDialogElement).close(); return }
            "close-rename" -> { (qq("#renameDialog") as HTMLDialogElement).close(); return }
            "add-endpoint" -> { openEndpoint(null); return }
            "edit-endpoint" -> { openEndpoint(list(endpoints)[(js("Number")(b.getAttribute("data-index")) as Double).toInt()]); return }
            "select-session" -> {
                selected = js("Number")(b.getAttribute("data-id")) as Double
                window.history.replaceState(null, "", "?id=" + str(selected))
                detail = null; detailShell = null; renderSessions(); launchPage { poll(true) }; return
            }
            "session-tab" -> { tab = b.getAttribute("data-tab") ?: tab; renderSessions(); icons(); return }
            "deselect-model" -> { modelsSelected.remove(b.getAttribute("data-model")); renderModelRows(); icons(); return }
            "export-activity" -> { val v: dynamic = obj(); v.atMs = nowMs(); v.calls = calls; v.standings = standings; exportJson(v, "mux-activity.json"); return }
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
        document.addEventListener("click", { onClick(it) })
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
            }
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
        val navs = document.querySelectorAll("[data-page=\"$page\"]")
        for (i in 0 until navs.length) (navs.item(i) as Element).setAttribute("aria-current", "page")
        html("#pageActions", if (page == "keys") "<a href=\"/mux/stats\">Quota activity " + icon("arrow-up-right") + "</a>"
        else if (page == "stats") btn("export-activity", "Export activity", "download")
        else "<button class=\"primary\" data-action=\"new\">" + icon("plus") + "New session</button>")
        selected = URLSearchParams(window.location.search).get("id")?.let { (js("Number")(it) as Double).takeIf { n -> n != 0.0 && !n.isNaN() } }
        render()
        launchPage { poll(true) }
        fun loop() { launchPage { poll(); setTimeout(2000) { loop() } } }
        setTimeout(2000) { loop() }
    }
}
