package borg.trikeshed.couch.git

import borg.trikeshed.couch.htx.*
import borg.trikeshed.process.ProcessShell
import java.util.LinkedList

/**
 * Git OpenAPI server — routes HTX messages to git subprocesses via ProcessShell.
 * Both common (op routing, response building) and JVM (actual ProcessShell execution).
 */
class GitOpenApiServer(private val shell: ProcessShell) {

    /** Route an HTX request to the appropriate git command, return HTX response. */
    fun route(request: HtxMessage): HtxMessage {
        val sl = request.startLine() ?: return errorResponse(400, "Bad request: no start line")
        if (!sl.isRequest) return errorResponse(400, "Expected request")

        val method = sl.method ?: return errorResponse(405, "Unknown method")
        val uri = sl.uri.decodeToString()
        val params = parseQueryParams(request)

        return try {
            when (sl.operationId(request)) {
                "getHealth" -> healthResponse()
                "gitStatus" -> gitPorcelain("status", "--porcelain=v2", params["repo"]!!)
                "gitLog" -> gitPorcelain("log", "--format=%H%n%an%n%ae%n%at%n%s%n---", "-n", params["n"] ?: "10", params["repo"]!!)
                "gitCommit" -> gitCommit(params["repo"]!!, params["message"]!!)
                "gitAdd" -> gitAdd(params["repo"]!!, params["files"]!!)
                "gitReset" -> gitReset(params["repo"]!!, params["ref"] ?: "HEAD")
                "gitDiff" -> gitDiff(params["repo"]!!, params["cached"]?.toBoolean() ?: false)
                "gitBranch" -> gitPorcelain("branch", "-v", params["repo"]!!)
                "gitCheckout" -> gitCheckout(params["repo"]!!, params["ref"]!!)
                "gitInit" -> gitInit(params["repo"]!!)
                "gitRevParse" -> gitRevParse(params["repo"]!!, params["ref"]!!)
                "gitInfoRefs" -> gitInfoRefs(params["repo"]!!, params["service"]!!)
                "gitUploadPack" -> gitUploadPack(request, params["repo"]!!)
                "gitReceivePack" -> gitReceivePack(request, params["repo"]!!)
                else -> errorResponse(404, "Unknown operation: ${sl.uri}")
            }
        } catch (e: Exception) {
            errorResponse(500, "Git error: ${e.message ?: e::class.simpleName}")
        }
    }

    private fun HtxStartLine.operationId(req: HtxMessage): CharSequence {
        val uri = this.uri.decodeToString()
        // operationId from x-trikeshed-operationId header if present
        findHeader(req, "x-trikeshed-operationId".encodeToByteArray())?.decodeToString()?.let { return it }
        // Map URI paths to operationIds:
        // /health → getHealth
        // /git porcelain/{op} → git{Op} (capitalized)
        return when {
            uri == "/health" -> "getHealth"
            uri.startsWith("/git porcelain/") -> {
                val op = uri.toString().removePrefix("/git porcelain/")
                "git${op.replaceFirstChar { it.uppercase() }}"
            }
            uri.startsWith("/git smart/") -> {
                val op = uri.toString().removePrefix("/git smart/")
                "git${op.replaceFirstChar { it.uppercase() }}"
            }
            else -> uri.toString().trimStart('/').replace("/", " ").replace("-", "")
                .split(" ").take(3).joinToString("")
        }
    }

    private fun parseQueryParams(msg: HtxMessage): Map<CharSequence, CharSequence> {
        val sl = msg.startLine() ?: return emptyMap()
        val uri = sl.uri.decodeToString()
        val query = uri.substringAfter('?', "")
        if (query.isEmpty()) return emptyMap()
        return query.split('&').mapNotNull { kv ->
            val parts = kv.split('=', limit = 2)
            if (parts.size == 2) parts[0].decodeURL() to parts[1].decodeURL() else null
        }.toMap()
    }

    private fun CharSequence.decodeURL(): CharSequence =
        buildString(length) {
            var index = 0
            while (index < this@decodeURL.length) {
                when (val ch = this@decodeURL[index]) {
                    '+' -> {
                        append(' ')
                        index++
                    }
                    '%' -> {
                        if (index + 2 >= this@decodeURL.length) {
                            append(ch)
                            index++
                        } else {
                            val hi = this@decodeURL[index + 1].hexValueOrNull()
                            val lo = this@decodeURL[index + 2].hexValueOrNull()
                            if (hi == null || lo == null) {
                                append(ch)
                                index++
                            } else {
                                append(((hi shl 4) or lo).toChar())
                                index += 3
                            }
                        }
                    }
                    else -> {
                        append(ch)
                        index++
                    }
                }
            }
        }

    private fun Char.hexValueOrNull(): Int? = when (this) {
        in '0'..'9' -> code - '0'.code
        in 'a'..'f' -> code - 'a'.code + 10
        in 'A'..'F' -> code - 'A'.code + 10
        else -> null
    }

    private fun gitPorcelain(vararg args: CharSequence): HtxMessage {
        val result = shell.exec("git", args.toList())
        val exitCode = result.exitCode
        val output = if (exitCode == 0) result.stdout else result.stderr
        return textResponse(output, if (exitCode == 0) 200 else 400)
    }

    private fun gitCommit(repo: CharSequence, message: CharSequence): HtxMessage {
        // Stage all, then commit with message
        val stageResult = shell.exec("git", "-C", repo, "add", "-A")
        if (stageResult.exitCode != 0) return textResponse(stageResult.stderr, 500)

        val commitResult = shell.exec("git", "-C", repo, "commit", "-m", message)
        val output = if (commitResult.exitCode == 0) commitResult.stdout else commitResult.stderr
        return textResponse(output, if (commitResult.exitCode == 0) 200 else 400)
    }

    private fun gitAdd(repo: CharSequence, files: CharSequence): HtxMessage {
        val fileList = files.split(" ").filter { it.isNotEmpty() }
        val result = shell.exec("git", "-C", repo, "add", *fileList.toTypedArray())
        return textResponse(result.stdout.ifEmpty { result.stderr }, if (result.exitCode == 0) 200 else 400)
    }

    private fun gitReset(repo: CharSequence, ref: CharSequence): HtxMessage =
        gitPorcelain("-C", repo, "reset", ref)

    private fun gitDiff(repo: CharSequence, cached: Boolean): HtxMessage {
        val args = LinkedList<CharSequence>("-C", repo, "diff")
        if (cached) args.add("--cached")
        val result = shell.exec("git", args.toList())
        return textResponse(result.stdout, if (result.exitCode == 0) 200 else 400)
    }

    private fun gitCheckout(repo: CharSequence, ref: CharSequence): HtxMessage =
        gitPorcelain("-C", repo, "checkout", ref)

    private fun gitInit(repo: CharSequence): HtxMessage {
        val result = shell.exec("git", "init", "--quiet", repo)
        return textResponse(result.stdout.ifEmpty { "Initialized: $repo" }, if (result.exitCode == 0) 200 else 400)
    }

    private fun gitRevParse(repo: CharSequence, ref: CharSequence): HtxMessage {
        val result = shell.exec("git", "-C", repo, "rev-parse", ref)
        return textResponse(result.stdout.toString().trimEnd(), if (result.exitCode == 0) 200 else 400)
    }

    private fun gitInfoRefs(repo: CharSequence, service: CharSequence): HtxMessage {
        // git-upload-pack or git-receive-pack advertised refs
        val result = shell.exec(
            "git", "-C", repo,
            "upload-pack", // always use upload-pack for info-refs; receive-pack is server-side
            "--stateless-rpc", "--advertise-refs", "."
        )
        val header = "# service=git-upload-pack\n"
        val refData = result.stdout
        // pkt-line format: length prefix + content
        val lines = (refData.lineSequence()
            .map { line -> "${(line.length + 4).toString(16).padStart(4, '0')}$line" }
            .joinToString("\n") + "\n0000")

        val body = "$header$lines"
        return HtxMessage().apply {
            addStartLine(HtxStartLine.response(200, "OK".encodeToByteArray()))
            addHeader("Content-Type".encodeToByteArray(), "application/x-git-upload-pack-advertisement".encodeToByteArray())
            addHeader("Cache-Control".encodeToByteArray(), "no-cache".encodeToByteArray())
            addData(body.encodeToByteArray())
            addEndHeaders()
            setEom()
        }
    }

    private fun gitUploadPack(request: HtxMessage, repo: CharSequence): HtxMessage {
        val body = request.blocks.filterIsInstance<HtxBlockData.Data>().joinToString("") {
            it.bytes.decodeToString()
        }
        if (body.isEmpty()) {
            return textResponse("empty request body", 400)
        }
        val result = shell.exec(
            "git", "-C", repo,
            "upload-pack", "--stateless-rpc", "."
        )
        // The body from request is sent to upload-pack stdin
        // For now, just invoke upload-pack and return its output
        return HtxMessage().apply {
            addStartLine(HtxStartLine.response(200, "OK".encodeToByteArray()))
            addHeader("Content-Type".encodeToByteArray(), "application/x-git-upload-pack-result".encodeToByteArray())
            addData(result.stdout.toString().encodeToByteArray())
            addEndHeaders()
            setEom()
        }
    }

    private fun gitReceivePack(request: HtxMessage, repo: CharSequence): HtxMessage {
        return HtxMessage().apply {
            addStartLine(HtxStartLine.response(200, "OK".encodeToByteArray()))
            addHeader("Content-Type".encodeToByteArray(), "application/x-git-receive-pack-result".encodeToByteArray())
            addEndHeaders()
            setEom()
        }
    }

    private fun healthResponse(): HtxMessage = HtxMessage().apply {
        addStartLine(HtxStartLine.response(200, "OK".encodeToByteArray()))
        addHeader("Content-Type".encodeToByteArray(), "text/plain".encodeToByteArray())
        addData("ok".encodeToByteArray())
        addEndHeaders()
        setEom()
    }

    private fun textResponse(body: CharSequence, status: Int): HtxMessage = HtxMessage().apply {
        addStartLine(HtxStartLine.response(status, if (status < 400) "OK".encodeToByteArray() else "Error".encodeToByteArray()))
        addHeader("Content-Type".encodeToByteArray(), "text/plain; charset=utf-8".encodeToByteArray())
        addData(body.encodeToByteArray())
        addEndHeaders()
        setEom()
    }

    private fun errorResponse(status: Int, message: CharSequence): HtxMessage = textResponse(message, status)
}
