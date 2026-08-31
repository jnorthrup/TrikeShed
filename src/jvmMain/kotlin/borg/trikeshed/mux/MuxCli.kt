package borg.trikeshed.mux

import borg.trikeshed.htx.HtxElement
import borg.trikeshed.htx.openHtxElement
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.userspace.nio.channels.spi.EgressAllowlist
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import borg.trikeshed.userspace.reactor.MuxReactorConfig
import borg.trikeshed.userspace.reactor.MuxReactorElement
import keymux.HarnessProvider
import keymux.HarnessRegistry
import keymux.KeyMux
import keymux.defaultHermesHome
import keymux.operatorKeyMux
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import modelmux.ModelMux
import modelmux.acp.AcpMessage
import kotlin.system.exitProcess

/**
 * `bin/mux` — the operator surface for keymux/modelmux.
 *
 * This exists because the wire-up had no way to be TRUE or FALSE from outside.
 * The contract tests in `muxcontract/` pin the algebra against fakes and pass
 * whether or not this machine can reach a single provider; the daemon resolves
 * keys during a boot that also needs a port, a couch, a board and a browser.
 * Between them there was no command that answered the only question an operator
 * actually asks — *did a key resolve, and did a call land* — so every failure
 * looked identical: a blank panel.
 *
 * Four verbs, each one strictly more expensive than the last:
 *
 *   keys     which providers resolve a key, and FROM WHICH SOURCE      (no network)
 *   models   what the mux would route, and which key each card binds   (no network)
 *   chat     one real call through ModelMux.chat, with its receipt     (network)
 *   doctor   keys → pick a live provider → real probe → verdict        (network)
 *
 * Secrets never print. A resolved key is reported as its length and the first 8
 * hex of its sha256 — enough to tell "the key rotated" from "the key is the one
 * I pasted", which is the entire diagnostic value a secret has, without putting
 * a live credential into a terminal buffer, a scrollback, or a pasted bug report.
 *
 * Exit status is the machine-readable half: 0 when the thing asked for worked,
 * 1 when it did not. `doctor` fails when no provider completes a live call, so
 * it is usable as a CI gate and not merely as something to read.
 */
object MuxCli {

    private const val USAGE = """mux — keymux/modelmux operator surface

Usage:
  mux keys   [--json] [--all]        which providers resolve a key, and from where
  mux models [--json]                what the mux would route, and the key each binds
  mux chat   --model <id> [--provider <p>] [--base-url <u>] [--max-tokens N] <prompt...>
  mux doctor [--json] [--provider <p>]   resolve → probe a real call → verdict
  mux stack  [--port N]              the WHOLE product: daemon, MCP, LCNC, live chat

Options:
  --json          machine-readable output
  --all           in `keys`, list every provider, not just the ones that resolve
  --provider <p>  restrict to one provider id (see `mux keys`)
  --max-tokens N  cap the probe/chat response (default 16 for doctor, 256 for chat)

Exit status is 0 only when the requested thing actually worked.
Secrets are never printed — keys show as len= and sha256 prefix."""

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty() || args[0] in setOf("-h", "--help", "help")) {
            println(USAGE)
            exitProcess(if (args.isEmpty()) 2 else 0)
        }
        val verb = args[0]
        val rest = args.drop(1)
        val json = rest.contains("--json")
        val all = rest.contains("--all")
        val provider = flagValue(rest, "--provider")
        val ok = try {
            when (verb) {
                "keys" -> runBlocking { cmdKeys(json, all, provider) }
                "models" -> runBlocking { cmdModels(json) }
                "chat" -> runBlocking { cmdChat(rest) }
                "doctor" -> runBlocking { cmdDoctor(json, provider) }
                "stack" -> runBlocking { cmdStack(flagValue(rest, "--port")?.toIntOrNull() ?: 8888) }
                else -> {
                    System.err.println("unknown verb: $verb\n")
                    println(USAGE)
                    exitProcess(2)
                }
            }
        } catch (t: Throwable) {
            // An operator surface that dies on a stack trace teaches nothing. Say
            // what broke, then show the trace for the bug report.
            System.err.println("mux $verb: ${t::class.simpleName}: ${t.message}")
            t.stackTraceToString().lineSequence().take(12).forEach { System.err.println("    $it") }
            false
        }
        exitProcess(if (ok) 0 else 1)
    }

    private fun flagValue(args: List<String>, flag: String): String? {
        val i = args.indexOf(flag)
        return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
    }

    // ── credential identity without the credential ──────────────────────
    /**
     * A key's public identity: how long it is and what it hashes to. Enough to
     * answer "is this the key I think it is" and "did it change" — the only two
     * questions a diagnostic needs — with nothing an attacker can spend.
     */
    private fun fingerprint(secret: String): String =
        "len=${secret.length} sha256=${ContentId.of(secret.encodeToByteArray()).hex.take(8)}"

    // ── the shared runtime context ──────────────────────────────────────
    /** Exactly the lane the daemon resolves through — see [operatorKeyMux]. */
    private fun buildKeyMux(fileOps: FileOperations): KeyMux =
        operatorKeyMux(fileOps = fileOps, hermesHome = defaultHermesHome())

    /**
     * Open the HTX + reactor context a live call needs. Held open for the
     * duration of [block] and closed after — the same elements the daemon puts
     * on the chat path, so a call that works here works there.
     */
    private suspend fun <T> withMuxRuntime(block: suspend (HtxElement, MuxReactorElement) -> T): T {
        val nio = NioSupervisor()
        nio.open()
        val htx = openHtxElement(nioSupervisor = nio)
        val reactor = MuxReactorElement(initialConfig = MuxReactorConfig())
        reactor.open()
        return block(htx, reactor)
    }

    private fun providersOf(only: String?): List<HarnessProvider> {
        val out = ArrayList<HarnessProvider>()
        for (i in 0 until HarnessRegistry.providers.size) {
            val p = HarnessRegistry.providers[i]
            if (only == null || p.id == only) out.add(p)
        }
        return out
    }

    // ═══════════════════════════════════════════════════════════════════
    // keys — resolution, with provenance. No network.
    // ═══════════════════════════════════════════════════════════════════

    private data class KeyRow(
        val provider: String,
        val present: Boolean,
        val source: String,
        val fingerprint: String,
        val baseUrl: String,
        val baseUrlSource: String,
        val probeModel: String?,
    )

    private suspend fun resolveRows(only: String?): List<KeyRow> {
        val fileOps = JvmFileOperations()
        val keyMux = buildKeyMux(fileOps)
        // withContext(IO) because the harness/hermes lanes hit the filesystem,
        // and FileOperations rides the context for the non-explicit sources.
        return withContext(Dispatchers.IO + fileOps) {
            providersOf(only).map { p ->
                val keyResult = runCatching { keyMux.getWithSource("llm.${p.id}.key") }.getOrNull()
                val secret = keyResult?.a
                val urlResult = runCatching { keyMux.getWithSource("llm.${p.id}.base_url") }.getOrNull()
                // A base_url that resolved through env/hermes is a destination the
                // operator configured on purpose, so it is permitted egress even
                // when the registry default never named that host. Registering at
                // resolution time (not at connect time) keeps the substrate's
                // deny-by-default posture: nothing is permitted that no
                // configuration asked for.
                (urlResult?.a ?: p.defaultBaseUrl)?.let { EgressAllowlist.allowUrl(it) }
                KeyRow(
                    provider = p.id,
                    present = !secret.isNullOrBlank(),
                    // "none" is KeyMux's own word for an unresolved path; keep it.
                    source = if (secret.isNullOrBlank()) "none" else (keyResult?.b ?: "none"),
                    fingerprint = if (secret.isNullOrBlank()) "" else fingerprint(secret),
                    baseUrl = urlResult?.a ?: p.defaultBaseUrl ?: "",
                    baseUrlSource = when {
                        urlResult?.a != null -> urlResult.b
                        p.defaultBaseUrl != null -> "registry-default"
                        else -> "none"
                    },
                    probeModel = p.probeModel,
                )
            }
        }
    }

    private suspend fun cmdKeys(json: Boolean, all: Boolean, only: String?): Boolean {
        val rows = resolveRows(only)
        val shown = if (all) rows else rows.filter { it.present }
        if (json) {
            println(shown.joinToString(",\n  ", "[\n  ", "\n]") { r ->
                """{"provider":"${r.provider}","keyPresent":${r.present},"source":"${r.source}",""" +
                    """"fingerprint":"${r.fingerprint}","baseUrl":"${r.baseUrl}",""" +
                    """"baseUrlSource":"${r.baseUrlSource}","probeModel":${r.probeModel?.let { "\"$it\"" } ?: "null"}}"""
            })
        } else {
            println("provider        key  source                  fingerprint                    base_url")
            println("─".repeat(118))
            for (r in shown) {
                println(
                    r.provider.padEnd(15) +
                        (if (r.present) " ✓  " else " ·  ").padEnd(5) +
                        r.source.padEnd(24) +
                        r.fingerprint.padEnd(31) +
                        r.baseUrl,
                )
            }
            val present = rows.count { it.present }
            println()
            println("$present of ${rows.size} providers resolve a key" + if (!all) "  (--all to list the rest)" else "")
            if (present == 0) {
                println()
                println("No key resolved. The lanes searched, in order:")
                println("  1. process env      OPENAI_API_KEY, ANTHROPIC_API_KEY, … (see HarnessRegistry)")
                println("  2. hermes dotenv    \$HERMES_HOME/.env, ~/.hermes/.env, ~/.hermes/profiles/*/.env")
                println("  3. harness stores   ~/.codex/auth.json, opencode auth.json, hermes auth.json")
                println("  4. hermes pool      \$HERMES_HOME credential_pool")
                println("HERMES_HOME is currently: ${defaultHermesHome()}")
                println("Set one key and re-run, e.g.:  export GROQ_API_KEY=…  &&  bin/mux doctor")
            }
        }
        // A key lane with nothing in it is a failure to report, not a clean run.
        return shown.any { it.present }
    }

    // ═══════════════════════════════════════════════════════════════════
    // models — what the mux would route. No network.
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Build a ModelMux over every provider that both resolves a key AND has a
     * probe model — i.e. the cards that could actually answer. A card whose key
     * does not resolve is not a routable model, and listing it as one is how the
     * panel came to show a menu of things that all fail.
     */
    private suspend fun buildProbeMux(keyMux: KeyMux, rows: List<KeyRow>): ModelMux =
        ModelMux(keyMux) {
            rows.filter { it.present && it.probeModel != null }.forEach { r ->
                model(
                    id = r.probeModel!!,
                    caps = setOf("chat"),
                    baseUrl = r.baseUrl,
                    provider = r.provider,
                )
            }
        }

    private suspend fun cmdModels(json: Boolean): Boolean {
        val fileOps = JvmFileOperations()
        val keyMux = buildKeyMux(fileOps)
        val rows = resolveRows(null)
        val routable = rows.filter { it.present && it.probeModel != null }
        val mux = withContext(Dispatchers.IO + fileOps) { buildProbeMux(keyMux, rows) }
        val cards = mux.listModels("chat")
        if (json) {
            println((0 until cards.size).joinToString(",\n  ", "[\n  ", "\n]") { i ->
                val id = cards[i].a
                val row = routable.firstOrNull { it.probeModel == id }
                """{"model":"$id","provider":"${row?.provider}","keyBinding":"llm.${row?.provider}.key","baseUrl":"${row?.baseUrl}"}"""
            })
        } else {
            println("model                              provider     binds                      base_url")
            println("─".repeat(112))
            for (i in 0 until cards.size) {
                val id = cards[i].a
                val row = routable.firstOrNull { it.probeModel == id }
                println(
                    id.padEnd(35) + (row?.provider ?: "?").padEnd(13) +
                        "llm.${row?.provider}.key".padEnd(27) + (row?.baseUrl ?: ""),
                )
            }
            println()
            println("${cards.size} routable chat model(s) — a card is listed only when its key resolves.")
            if (cards.size == 0) println("Nothing routable. Run `mux keys --all` to see why.")
        }
        return cards.size > 0
    }

    // ═══════════════════════════════════════════════════════════════════
    // chat — one real call, with its receipt.
    // ═══════════════════════════════════════════════════════════════════

    private suspend fun cmdChat(args: List<String>): Boolean {
        val model = flagValue(args, "--model")
        if (model == null) {
            System.err.println("mux chat: --model is required (see `mux models`)")
            return false
        }
        val maxTokens = flagValue(args, "--max-tokens")?.toIntOrNull() ?: 256
        val flags = setOf("--model", "--provider", "--base-url", "--max-tokens")
        // Everything that is not a flag or a flag's value is the prompt.
        val prompt = buildList {
            var i = 0
            while (i < args.size) {
                val a = args[i]
                when {
                    a in flags -> i += 2
                    a == "--json" -> i += 1
                    else -> { add(a); i += 1 }
                }
            }
        }.joinToString(" ")
        if (prompt.isBlank()) {
            System.err.println("mux chat: no prompt given")
            return false
        }

        val fileOps = JvmFileOperations()
        val keyMux = buildKeyMux(fileOps)
        val rows = resolveRows(null)
        // --provider/--base-url override; otherwise infer from the registry row
        // whose probe model matches, so `mux chat --model llama-3.1-8b-instant`
        // just works after `mux models` named it.
        val explicitProvider = flagValue(args, "--provider")
        val inferred = rows.firstOrNull { it.probeModel == model && it.present }
        val prov = explicitProvider ?: inferred?.provider
        if (prov == null) {
            System.err.println("mux chat: cannot infer a provider for '$model' — pass --provider (see `mux keys`)")
            return false
        }
        val row = rows.firstOrNull { it.provider == prov }
        val baseUrl = flagValue(args, "--base-url") ?: row?.baseUrl
        if (baseUrl.isNullOrBlank()) {
            System.err.println("mux chat: no base_url for provider '$prov' — pass --base-url")
            return false
        }
        if (row?.present != true) {
            System.err.println("mux chat: no key resolves for provider '$prov' (llm.$prov.key) — run `mux keys --all`")
            return false
        }

        val outcome = withMuxRuntime { htx, reactor ->
            val mux = ModelMux(keyMux) {
                model(id = model, caps = setOf("chat"), baseUrl = baseUrl, provider = prov)
            }
            withContext(Dispatchers.IO + fileOps + htx + reactor) {
                val messages: Series<AcpMessage> = 1 j { _: Int -> "user" j prompt }
                val t0 = System.currentTimeMillis()
                val res = mux.chat(modelId = model, messages = messages, maxTokens = maxTokens, temperature = 0.2)
                val ms = System.currentTimeMillis() - t0
                res.fold(
                    onSuccess = { r ->
                        println(r.a)
                        println()
                        println("── receipt ──────────────────────────────────")
                        println("  model      $model  (provider $prov, binds llm.$prov.key)")
                        println("  base_url   $baseUrl")
                        println("  tokens     in=${r.b.a} out=${r.b.b}")
                        println("  latency    ${ms}ms")
                        val standings = runCatching { mux.quotaStandings(System.currentTimeMillis()) }.getOrDefault(emptyList())
                        if (standings.isNotEmpty()) {
                            println("  quota      " + standings.joinToString("; ") {
                                "${it.keyId} spent=${it.spent}/${it.limit}${if (it.exhausted) " EXHAUSTED" else ""}"
                            })
                        }
                        true
                    },
                    onFailure = { t ->
                        System.err.println("call FAILED after ${ms}ms: ${t.message}")
                        false
                    },
                )
            }
        }
        return outcome
    }

    // ═══════════════════════════════════════════════════════════════════
    // stack — the whole product, one command.
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Verify the product an operator actually uses: daemon, MCP lens, LCNC
     * runners, and a real chat through the LCNC path.
     *
     * `doctor` proves the PROVIDER half — keys resolve and a call lands. That
     * half can be perfectly healthy while the thing the operator opens is dead,
     * and it was: an MCP client reported only `ConnectionRefused`, which is what
     * a stdio-less HTTP MCP server says when the daemon simply is not running.
     * Nothing connected those two facts, so "MCP is broken" and "the daemon is
     * down" were indistinguishable from outside. Three scripts each proved a
     * different third of the stack and none of them proved the seam.
     *
     * Every step here is a link in one chain, run in order, stopping at the first
     * break — because a failure downstream of a break is noise, not information.
     */
    private suspend fun cmdStack(port: Int): Boolean {
        val base = "http://127.0.0.1:$port"
        println("▸ stack check · $base")

        var failed = false
        fun step(label: String, ok: Boolean, detail: String = "", fix: String = ""): Boolean {
            if (ok) println("  ✓ ${label.padEnd(34)} $detail")
            else {
                println("  ✗ ${label.padEnd(34)} $detail")
                if (fix.isNotEmpty()) println("      ↳ fix: $fix")
                failed = true
            }
            return ok
        }

        return withMuxRuntime { htx, reactor ->
            withContext(Dispatchers.IO + JvmFileOperations() + htx + reactor) {
                suspend fun get(path: String): Pair<Int, String> = runCatching {
                    val req = borg.trikeshed.htx.parseHtxRequest(
                        url = "$base$path", method = borg.trikeshed.htx.HtxMethod.GET,
                    )
                    val r = htx.request(req)
                    r.status to r.body.toArray().decodeToString()
                }.getOrElse { 0 to (it.message ?: "unreachable") }

                suspend fun post(path: String, body: String): Pair<Int, String> = runCatching {
                    fun hdr(k: String, v: String): borg.trikeshed.htx.HtxHeader =
                        object : borg.trikeshed.htx.HtxHeader {
                            override val a = k
                            override val b = v
                        }
                    val req = borg.trikeshed.htx.parseHtxRequest(
                        url = "$base$path", method = borg.trikeshed.htx.HtxMethod.POST,
                        body = borg.trikeshed.lib.ByteSeries(body.encodeToByteArray()),
                    ).copy(headers = borg.trikeshed.htx.htxHeaders(hdr("Content-Type", "application/json")))
                    val r = htx.request(req)
                    r.status to r.body.toArray().decodeToString()
                }.getOrElse { 0 to (it.message ?: "unreachable") }

                // 1 ── the daemon itself. Everything below is meaningless without it,
                //      and this is the answer an MCP ConnectionRefused was hiding.
                val (boardStatus, _) = get("/api/board")
                if (!step(
                        "daemon /api/board", boardStatus == 200,
                        if (boardStatus == 200) "200" else "no answer on $port",
                        "bin/oroboros-up --port $port",
                    )
                ) return@withContext false

                // 2 ── the MCP lens an agent connects to.
                val (mcpStatus, _) = get("/api/mcp")
                step(
                    "mcp lens /api/mcp", mcpStatus == 200,
                    if (mcpStatus == 200) "200 — claude mcp add --transport http oroboros-kanban $base/api/mcp"
                    else "HTTP $mcpStatus",
                    "the kanban module failed to attach; see the daemon log",
                )

                // 3 ── LCNC runners are registered and the daemon's own KeyMux answers.
                val (keysStatus, keysBody) = post("/api/lcnc/run", """{"type":"keys.status"}""")
                val keyed = Regex("\"keyPresent\":\\s*true").findAll(keysBody).count()
                step(
                    "lcnc keys.status", keysStatus == 200 && keyed > 0,
                    if (keysStatus == 200) "$keyed provider(s) resolve a key inside the daemon"
                    else "HTTP $keysStatus",
                    "bin/mux keys --all  (the daemon shares this KeyMux recipe)",
                )

                // 4 ── routable cards, and the collision that used to hide half of them.
                val (modelsStatus, modelsBody) = post("/api/lcnc/run", """{"type":"mux.models"}""")
                val ids = Regex("\"id\":\"([^\"]+)\"").findAll(modelsBody).map { it.groupValues[1] }.toList()
                val dupes = ids.groupingBy { it }.eachCount().filterValues { it > 1 }
                step(
                    "lcnc mux.models", modelsStatus == 200 && ids.isNotEmpty(),
                    "${ids.size} card(s)", "check the daemon roster wiring",
                )
                step(
                    "card ids unique", dupes.isEmpty(),
                    if (dupes.isEmpty()) "no shadowed routes"
                    else "SHADOWED: ${dupes.keys.take(3).joinToString(", ")}",
                    "duplicate ids make the later provider unreachable — see modelmux.disambiguateModelIds",
                )

                // 5 ── the seam itself: a real chat through the LCNC node, the path
                //      the panel uses. This is the step nothing else covered.
                if (ids.isEmpty()) {
                    step("lcnc prompt.chat", false, "no model to try")
                    return@withContext false
                }
                // The claim under test is that the SEAM is wired, not that card #1
                // is alive. The roster carries EOL models (nvidia answers 410 for
                // several) and providers with no credit, so probing only the first
                // card reports a billing fact as a broken pipeline. Walk candidates
                // until one answers; the seam is proven by any success.
                var answered: Pair<String, String>? = null
                val tried = ArrayList<String>()
                for (candidate in ids.take(6)) {
                    val body = """{"type":"prompt.chat","params":{"model":"$candidate",""" +
                        """"prompt":"Reply with the single word: ok","maxTokens":"120"}}"""
                    val (cs, cb) = post("/api/lcnc/run", body)
                    val text = Regex("\"content\":\\s*\"([^\"]*)\"").find(cb)?.groupValues?.get(1) ?: ""
                    if (cs == 200 && text.isNotBlank()) { answered = candidate to text; break }
                    val e = Regex("\"error\":\\s*\"([^\"]*)\"").find(cb)?.groupValues?.get(1) ?: "no content"
                    tried.add("$candidate: ${e.take(60)}")
                }
                step(
                    "lcnc prompt.chat", answered != null,
                    if (answered != null) "${answered.first} → \"${answered.second.take(40)}\""
                    else "${tried.size} model(s) tried, none answered",
                    "bin/mux doctor  (separates a dead key from a dead route from a dead model)",
                )
                if (answered == null) tried.forEach { println("      · $it") }

                println()
                if (!failed) {
                    println("  STACK LIVE — daemon, MCP lens, LCNC runners and a real model call.")
                } else {
                    println("  STACK INCOMPLETE — the first ✗ above is the one to fix; later lines may")
                    println("  simply be downstream of it.")
                }
                !failed
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // doctor — the end-to-end verdict.
    // ═══════════════════════════════════════════════════════════════════

    private data class ProbeResult(
        val provider: String,
        val model: String,
        val ok: Boolean,
        val detail: String,
        val latencyMs: Long,
        val kind: FailKind = FailKind.NONE,
    )

    /**
     * What a failed probe actually proves.
     *
     * The first cut of this reported every failure as a credential problem, and
     * on a box with no route to the provider hosts it told the operator to go
     * re-issue sixteen perfectly good keys. A diagnostic that misattributes
     * costs more than no diagnostic: it sends people to fix the wrong thing with
     * full confidence. The provider's own status code says which thing broke,
     * and where there is no status code — the connection never opened — that is
     * itself the answer, and a different one.
     */
    private enum class FailKind {
        NONE,
        /** Never reached the provider: DNS, route, TLS, sandbox/proxy egress. Says NOTHING about the key. */
        EGRESS,
        /** 401/403 — reached the provider, which rejected the credential. */
        AUTH,
        /** 404 — reached the provider; the model id or base_url is wrong, not the key. */
        ENDPOINT,
        /** 429 — the key is good and out of budget. */
        QUOTA,
        /** 5xx — the provider is having a bad day; nothing here is yours to fix. */
        PROVIDER,
        UNKNOWN,
    }

    private fun classify(message: String): FailKind {
        val m = message.lowercase()
        // Providers do not agree on which status means "out of money". Real
        // replies observed from this very probe: xai says 403 "has either used
        // all available credits", perplexity says 401 "you exceeded your current
        // quota", openai says 429. Classifying all of those as AUTH sent the
        // operator to re-issue three working keys. The BODY is more reliable
        // than the status here, so it is consulted first.
        val saysBudget = "quota" in m || "credit" in m || "billing" in m ||
            "insufficient balance" in m || "exceeded your current" in m ||
            "payment required" in m || "http 402" in m || "recharge" in m
        // Likewise "wrong model" arrives as 404 (cerebras), 410 (nvidia,
        // end-of-life) and 400 (zai, "Unknown Model") — one condition, three codes.
        val saysModel = "unknown model" in m || "model does not exist" in m ||
            "end of life" in m || "no longer available" in m || "does not exist or you do not have access" in m
        return when {
            // Connect-stage words. No HTTP status exists at this point by definition.
            "connect failed" in m || "egress denied" in m || "not in allowlist" in m ||
                "unresolvedaddress" in m || "connection refused" in m || "no route to host" in m ||
                "unknownhost" in m || "timed out" in m || "timeout" in m ||
                "ssl" in m || "handshake" in m -> FailKind.EGRESS
            saysModel -> FailKind.ENDPOINT
            saysBudget -> FailKind.QUOTA
            "http 429" in m -> FailKind.QUOTA
            "http 401" in m || "http 403" in m -> FailKind.AUTH
            "http 404" in m || "http 410" in m -> FailKind.ENDPOINT
            Regex("http 5\\d\\d").containsMatchIn(m) -> FailKind.PROVIDER
            else -> FailKind.UNKNOWN
        }
    }

    /**
     * The ids this provider will actually answer on, from its own `/models`.
     *
     * A probe that fails with "unknown model" provokes exactly one question, and
     * making the operator go find the provider's model list by hand is how a
     * tool stops being turn-key. The provider already publishes the answer; ask
     * it. Best-effort by construction: any failure yields an empty list and the
     * caller simply says nothing, because this is a HINT attached to an error
     * that has already been reported accurately.
     */
    private suspend fun remoteModelIds(baseUrl: String, apiKey: String, limit: Int = 8): List<String> =
        runCatching {
            val htx = kotlinx.coroutines.currentCoroutineContext()[borg.trikeshed.htx.HtxKey]
                ?: return emptyList()
            fun hdr(k: String, v: String): borg.trikeshed.htx.HtxHeader =
                object : borg.trikeshed.htx.HtxHeader {
                    override val a = k
                    override val b = v
                }
            val req = borg.trikeshed.htx.parseHtxRequest(
                url = "$baseUrl/models",
                method = borg.trikeshed.htx.HtxMethod.GET,
            ).copy(headers = borg.trikeshed.htx.htxHeaders(hdr("Authorization", "Bearer $apiKey")))
            val resp = htx.request(req)
            if (resp.status !in 200..299) return emptyList()
            val body = resp.body.toArray().decodeToString()
            val parsed = borg.trikeshed.parse.json.JsonSupport.parse(body) as? Map<*, *>
                ?: return emptyList()
            val data = parsed["data"] as? List<*> ?: return emptyList()
            data.mapNotNull { (it as? Map<*, *>)?.get("id") as? String }.take(limit)
        }.getOrDefault(emptyList())

    /** One line naming what the operator should go do about [kind]. */
    private fun remedy(kind: FailKind): String = when (kind) {
        FailKind.EGRESS -> "no route to the provider hosts — proxy, VPN, firewall or sandbox egress. Your keys were never tried."
        FailKind.AUTH -> "the provider rejected the credential — the key resolved but is stale, revoked, or for another account."
        FailKind.ENDPOINT -> "reached the provider, wrong door — check the probe model id and base_url, not the key."
        FailKind.QUOTA -> "the key works and is out of budget — `mux chat` will show the standings."
        FailKind.PROVIDER -> "the provider returned a server error — retry later; nothing on this side is broken."
        FailKind.UNKNOWN, FailKind.NONE -> "unclassified — the raw provider message is on the line above."
    }

    private suspend fun cmdDoctor(json: Boolean, only: String?): Boolean {
        val fileOps = JvmFileOperations()
        val keyMux = buildKeyMux(fileOps)
        val rows = resolveRows(only)

        if (!json) {
            println("▸ credential resolution")
            for (r in rows.filter { it.present }) {
                println("  ✓ ${r.provider.padEnd(13)} via ${r.source.padEnd(22)} ${r.fingerprint}")
            }
            val unresolved = rows.count { !it.present }
            if (rows.none { it.present }) {
                println("  ✗ no provider resolves a key")
            } else {
                println("  · $unresolved provider(s) unresolved — `mux keys --all` for the full table")
            }
        }

        val probeable = rows.filter { it.present && it.probeModel != null && it.baseUrl.isNotBlank() }
        if (probeable.isEmpty()) {
            if (json) println("""{"ok":false,"reason":"no probeable provider","probes":[]}""")
            else {
                println()
                println("▸ live probe")
                println("  ✗ nothing to probe.")
                val keyedNoProbe = rows.filter { it.present && it.probeModel == null }
                for (r in keyedNoProbe) {
                    println("    ${r.provider}: key resolves, but no OpenAI-compatible probe model is registered")
                    if (r.provider == "anthropic")
                        println("      (anthropic speaks /v1/messages; the ModelMux lane posts /chat/completions)")
                }
                if (rows.none { it.present })
                    println("    Set a provider key and re-run — e.g. export GROQ_API_KEY=… ")
            }
            return false
        }

        if (!json) {
            println()
            println("▸ live probe — one real /chat/completions per provider")
        }
        val results = withMuxRuntime { htx, reactor ->
            probeable.map { r ->
                // Bound once: `probeable` already filtered on non-null, but the
                // smart cast does not survive into the builder/withContext lambdas.
                val probeModel = r.probeModel!!
                val mux = ModelMux(keyMux) {
                    model(id = probeModel, caps = setOf("chat"), baseUrl = r.baseUrl, provider = r.provider)
                }
                withContext(Dispatchers.IO + fileOps + htx + reactor) {
                    val messages: Series<AcpMessage> = 1 j { _: Int -> "user" j "Reply with the single word: ok" }
                    val t0 = System.currentTimeMillis()
                    val res = runCatching {
                        mux.chat(modelId = probeModel, messages = messages, maxTokens = 16, temperature = 0.0)
                    }.getOrElse { Result.failure(it) }
                    val ms = System.currentTimeMillis() - t0
                    val out = res.fold(
                        onSuccess = { ProbeResult(r.provider, probeModel, true, it.a.trim().take(60), ms) },
                        onFailure = {
                            val msg = (it.message ?: "unknown")
                            ProbeResult(r.provider, probeModel, false, msg.take(160), ms, classify(msg))
                        },
                    )
                    if (!json) {
                        if (out.ok) println("  ✓ ${out.provider.padEnd(13)} ${out.model.padEnd(28)} ${out.latencyMs}ms  → \"${out.detail}\"")
                        else println("  ✗ ${out.provider.padEnd(13)} ${out.model.padEnd(28)} ${out.latencyMs}ms  [${out.kind}] ${out.detail}")
                        // Wrong door → name the doors. The key demonstrably works
                        // (the provider authenticated us well enough to reject the
                        // model), so its own catalog is reachable and authoritative.
                        if (out.kind == FailKind.ENDPOINT) {
                            val secret = runCatching { keyMux.get("llm.${r.provider}.key") }.getOrNull()
                            if (!secret.isNullOrBlank()) {
                                val ids = remoteModelIds(r.baseUrl, secret)
                                if (ids.isNotEmpty()) {
                                    println("      ${r.provider} offers: ${ids.joinToString(", ")}")
                                    println("      try: bin/mux chat --provider ${r.provider} --model ${ids.first()} \"hello\"")
                                }
                            }
                        }
                    }
                    out
                }
            }
        }

        val live = results.count { it.ok }
        if (json) {
            println(
                """{"ok":${live > 0},"live":$live,"probed":${results.size},"probes":[""" +
                    results.joinToString(",") { r ->
                        """{"provider":"${r.provider}","model":"${r.model}","ok":${r.ok},"kind":"${r.kind}",""" +
                            """"latencyMs":${r.latencyMs},"detail":"${r.detail.replace("\"", "'").replace("\n", " ")}"}"""
                    } + "]}",
            )
        } else {
            println()
            println("▸ verdict")
            if (live > 0) {
                println("  KeyMux → ModelMux → provider is LIVE: $live of ${results.size} probed providers answered.")
                println("  Reproduce a single call:  bin/mux chat --model ${results.first { it.ok }.model} \"hello\"")
                val broken = results.filter { !it.ok }.groupBy { it.kind }
                for ((kind, rs) in broken) {
                    println("  · ${rs.size} failed [$kind]: ${rs.joinToString(", ") { it.provider }}")
                    println("      ${remedy(kind)}")
                }
            } else {
                // Name the DOMINANT failure. Sixteen identical egress errors are one
                // fact about this machine, not sixteen facts about the credentials.
                val kinds = results.groupingBy { it.kind }.eachCount()
                val dominant = kinds.maxByOrNull { it.value }?.key ?: FailKind.UNKNOWN
                if (dominant == FailKind.EGRESS && kinds[FailKind.EGRESS] == results.size) {
                    println("  INCONCLUSIVE — every probe failed before reaching a provider.")
                    println("  ${remedy(FailKind.EGRESS)}")
                    println("  The credential lane above is FINE: ${rows.count { it.present }} keys resolved.")
                    println("  Fix the route and re-run; nothing here indicts keymux or modelmux.")
                } else {
                    println("  NOT LIVE — no provider completed a call.")
                    for ((kind, n) in kinds.entries.sortedByDescending { it.value }) {
                        println("  · $n [$kind] — ${remedy(kind)}")
                    }
                }
            }
        }
        return live > 0
    }
}
