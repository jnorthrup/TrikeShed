package borg.trikeshed.mux

import borg.trikeshed.agent.modelmux.PeerSettings

/** One model route, configured explicitly: the provider tag, the model id, its OpenAI-compatible base URL, and the env var that holds its key. */
data class ModelLane(val provider: String, val model: String, val baseUrl: String, val keyEnv: String)

/**
 * Command-line surface of the stdio MCP server. Everything that widens what the server can do is an explicit flag:
 * there is no environment discovery, no dotenv lookup and no default that spends money or runs a command.
 */
class McpStdioOptions(
    val scopes: Set<String>,
    val store: String?,
    val allowWrite: Boolean,
    val allowExec: Boolean,
    val journalDir: String?,
    val oversightMlx: Boolean,
    val lanes: List<ModelLane>,
    val maxInFlight: Int,
    val maxLineBytes: Int,
    val help: Boolean,
    /** A Kubo node to read blocks this server does not hold from. Absent: nothing is ever fetched from anyone. */
    val peer: PeerSettings? = null,
) {
    companion object {
        const val USAGE: String = """mcp-stdio — ModelMux + Zenith as an MCP server over stdio (newline-delimited JSON-RPC)

Usage: mcp-stdio [options]

Reads one JSON-RPC request per line on stdin and writes one reply per line on stdout. stdout carries ONLY protocol
lines; diagnostics go to stderr. Works as an MCP stdio server, under tcpd/inetd/socat (the socket is stdin/stdout),
or as a one-shot wrapper (printf '{...}' | mcp-stdio).

Shared context (ModelMux breadcrumbs):
  --scope NAME           serve this context scope (repeatable). With none, no context tools are registered.
  --store DIR            durable content-addressed store for context blocks (default: in memory, this process only)
  --allow-write          also register modelmux_context_put
  --peer-kubo-repo DIR   also read context blocks this server does not hold from the Kubo node whose repository is DIR
                         (absolute path). Nothing is discovered; blocks are asked for by identity only and verified here.
                         Needs at least one --scope.
  --peer-kubo-api ADDR   use that node's running daemon at ADDR (a loopback multiaddr such as /ip4/127.0.0.1/tcp/5001);
                         without it the node's repository is read offline
  --peer-kubo-bin PATH   the ipfs executable (default: ipfs on PATH)
  --peer-timeout-s N     bound for one block fetch, 1..3600 (default 30)
  --oversight mlx        System1 relevance review by the local 4-bit MLX Jeva (needs TRIKESHED_JEVA_* from
                         config/jeva-mlx.env.sh); without it, oversight=advisory|required reports UNAVAILABLE

Models (spends provider quota; keys come only from the named environment variables):
  --model-lane PROVIDER,MODEL,BASE_URL,KEY_ENV   (repeatable) register modelmux_models and modelmux_chat.
                         BASE_URL must be https, or http on loopback.

Zenith missions:
  --journal-dir DIR      directory for mission journals; registers zenith_mission_inspect and the run tool's journal option
  --allow-exec           register zenith_mission_run, which EXECUTES the plan's Bash with this process's permissions

Limits:
  --max-in-flight N      concurrent requests (1..64, default 8); extra requests are answered -32001, not queued
  --max-line-bytes N     largest request line (1024..16777216, default 1048576)

Unregistered tools do not exist: a server started without --allow-exec cannot be made to run a command."""

        private val PROVIDER = Regex("[a-z0-9][a-z0-9_-]{0,31}")
        private val KEY_ENV = Regex("[A-Z_][A-Z0-9_]{0,63}")
        private val LOOPBACK = Regex("http://(127\\.0\\.0\\.1|localhost|\\[::1])(:\\d{1,5})?(/.*)?")

        fun parse(args: Array<String>): McpStdioOptions {
            val scopes = LinkedHashSet<String>()
            val lanes = ArrayList<ModelLane>()
            var store: String? = null
            var journalDir: String? = null
            var allowWrite = false
            var allowExec = false
            var oversightMlx = false
            var maxInFlight = 8
            var maxLineBytes = 1_048_576
            var help = false
            var peerRepo: String? = null
            var peerApi: String? = null
            var peerBin: String? = null
            var peerTimeout: Int? = null

            var i = 0
            fun value(flag: String): String {
                require(i + 1 < args.size) { "$flag needs a value" }
                i++
                return args[i]
            }
            fun once(flag: String, current: Any?) = require(current == null) { "$flag given twice" }

            while (i < args.size) {
                val flag = args[i]
                when (flag) {
                    "-h", "--help" -> help = true
                    "--scope" -> {
                        val v = value(flag)
                        require(v.length in 1..256 && v == v.trim() && v.none { it < ' ' || it == '\u007f' }) { "--scope must be 1..256 printable characters" }
                        scopes.add(v)
                    }
                    "--store" -> { once(flag, store); store = value(flag).also { require(it.isNotBlank()) { "--store needs a directory" } } }
                    "--journal-dir" -> { once(flag, journalDir); journalDir = value(flag).also { require(it.isNotBlank()) { "--journal-dir needs a directory" } } }
                    "--peer-kubo-repo" -> { once(flag, peerRepo); peerRepo = value(flag) }
                    "--peer-kubo-api" -> { once(flag, peerApi); peerApi = value(flag) }
                    "--peer-kubo-bin" -> { once(flag, peerBin); peerBin = value(flag) }
                    "--peer-timeout-s" -> {
                        once(flag, peerTimeout)
                        peerTimeout = value(flag).toIntOrNull()?.takeIf { it in 1..3_600 } ?: throw IllegalArgumentException("--peer-timeout-s must be 1..3600")
                    }
                    "--allow-write" -> allowWrite = true
                    "--allow-exec" -> allowExec = true
                    "--oversight" -> {
                        val v = value(flag)
                        require(v == "mlx") { "--oversight supports only: mlx" }
                        oversightMlx = true
                    }
                    "--model-lane" -> lanes.add(lane(value(flag)).also { new ->
                        require(lanes.none { it.model == new.model }) { "model '${new.model}' is configured twice" }
                    })
                    "--max-in-flight" -> maxInFlight = value(flag).toIntOrNull()?.takeIf { it in 1..64 } ?: throw IllegalArgumentException("--max-in-flight must be 1..64")
                    "--max-line-bytes" -> maxLineBytes = value(flag).toIntOrNull()?.takeIf { it in 1_024..16_777_216 } ?: throw IllegalArgumentException("--max-line-bytes must be 1024..16777216")
                    else -> throw IllegalArgumentException("unknown option: ${flag.take(40)}")
                }
                i++
            }
            if (peerRepo == null) {
                require(peerApi == null && peerBin == null && peerTimeout == null) { "--peer-kubo-api, --peer-kubo-bin and --peer-timeout-s need --peer-kubo-repo" }
            } else {
                require(scopes.isNotEmpty()) { "--peer-kubo-repo needs at least one --scope: nothing is served by default" }
            }
            val peer = peerRepo?.let { PeerSettings(it, peerApi, peerBin ?: "ipfs", peerTimeout ?: 30) }
            return McpStdioOptions(scopes, store, allowWrite, allowExec, journalDir, oversightMlx, lanes, maxInFlight, maxLineBytes, help, peer)
        }

        private fun lane(spec: String): ModelLane {
            val parts = spec.split(',')
            require(parts.size == 4) { "--model-lane takes PROVIDER,MODEL,BASE_URL,KEY_ENV" }
            val (provider, model, baseUrl, keyEnv) = parts
            require(PROVIDER.matches(provider)) { "--model-lane provider must match ${PROVIDER.pattern}" }
            require(model.length in 1..256 && model == model.trim() && model.none { it < ' ' }) { "--model-lane model must be 1..256 printable characters" }
            require(baseUrl.startsWith("https://") && baseUrl.length > 8 || LOOPBACK.matches(baseUrl)) { "--model-lane BASE_URL must be https, or http on loopback" }
            require(KEY_ENV.matches(keyEnv)) { "--model-lane KEY_ENV must be an environment variable name" }
            return ModelLane(provider, model, baseUrl.trimEnd('/'), keyEnv)
        }
    }
}
