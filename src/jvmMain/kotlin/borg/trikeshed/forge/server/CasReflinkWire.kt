package borg.trikeshed.forge.server

import borg.trikeshed.btrfs.BtrfsReflinkStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.litebike.JvmKanbanServer
import java.nio.charset.StandardCharsets

/**
 * The daemon's NAMED HTTP surface onto the btrfs store's own primitives
 * (mission-002 decisions D6/D13).
 *
 * Three routes, all raw (the PUT route carries binary bodies ≥ 1 MiB, which the
 * text-decoded [JvmKanbanServer.ExtraRoute] surface cannot carry losslessly):
 *
 *  - `POST /api/cas/put`         — body bytes → [BtrfsReflinkStore.put]; answers the cid and
 *                                  the exact `<casRoot>/sha256/<2hex>/<62hex>` path written.
 *  - `POST /api/cas/materialize` — `{"cid":…,"topic":…,"path":…}` → the D13 MATERIALIZE
 *                                  primitive [BtrfsReflinkStore.reflinkReorganize], i.e.
 *                                  `cp --reflink=always` from the CAS blob to
 *                                  `<topicRoot>/<topic>/<path>`. Answers BOTH paths.
 *  - `GET  /api/cas/get?cid=…`   — the blob back, verified against its cid by the store.
 *
 * This wire is installed ONLY when the daemon selected the btrfs store
 * (`TRIKESHED_CAS=btrfs`); on a `FileCasStore` boot the routes are absent and answer 404,
 * because `reflinkCopy`/`reflinkReorganize` exist only on [BtrfsReflinkStore].
 */
class CasReflinkWire(
    private val store: BtrfsReflinkStore,
    private val casRoot: String,
) {
    companion object {
        const val PUT_ROUTE: String = "/api/cas/put"
        const val MATERIALIZE_ROUTE: String = "/api/cas/materialize"
        const val GET_ROUTE: String = "/api/cas/get"

        /** Prefix [JvmKanbanServer] hands to raw routes (binary bodies). */
        const val PREFIX: String = "/api/cas/"

        val ROUTES: List<Pair<String, String>> = listOf(
            "POST" to PUT_ROUTE, "POST" to MATERIALIZE_ROUTE, "GET" to GET_ROUTE,
        )

        /** Body of a raw HTTP/1.1 request frame: everything after CRLFCRLF, capped by Content-Length. */
        internal fun bodyOf(payload: ByteArray): ByteArray {
            var boundary = -1
            for (i in 0..payload.size - 4) {
                if (payload[i] == '\r'.code.toByte() && payload[i + 1] == '\n'.code.toByte() &&
                    payload[i + 2] == '\r'.code.toByte() && payload[i + 3] == '\n'.code.toByte()
                ) { boundary = i; break }
            }
            if (boundary < 0) return ByteArray(0)
            val start = boundary + 4
            val head = payload.decodeToString(0, boundary)
            val declared = head.split("\r\n")
                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull()
            val end = if (declared != null) minOf(payload.size, start + declared) else payload.size
            return if (end <= start) ByteArray(0) else payload.copyOfRange(start, end)
        }

        private fun jsonString(s: String): String = buildString {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r")
                else -> append(c)
            }
            append('"')
        }

        /** Minimal flat-JSON field read — the request bodies here are three string fields. */
        internal fun field(body: String, name: String): String? {
            val key = "\"$name\""
            val at = body.indexOf(key)
            if (at < 0) return null
            var i = body.indexOf(':', at + key.length)
            if (i < 0) return null
            i++
            while (i < body.length && body[i].isWhitespace()) i++
            if (i >= body.length || body[i] != '"') return null
            i++
            val sb = StringBuilder()
            while (i < body.length && body[i] != '"') {
                if (body[i] == '\\' && i + 1 < body.length) { i++; sb.append(body[i]) } else sb.append(body[i])
                i++
            }
            return sb.toString()
        }

        private fun query(path: String, name: String): String? =
            path.substringAfter('?', "").split('&')
                .firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
    }

    private fun json(status: Int, body: String) =
        JvmKanbanServer.HttpResponse(status, body, "application/json; charset=utf-8")

    suspend fun route(
        method: String,
        path: String,
        payload: ByteArray,
        respond: (suspend (ByteArray) -> Unit)? = null,
    ): JvmKanbanServer.HttpResponse? {
        val bare = path.substringBefore('?')
        if (!bare.startsWith(PREFIX)) return null

        return when {
            bare == PUT_ROUTE && method == "POST" -> {
                val bytes = bodyOf(payload)
                if (bytes.isEmpty()) return json(400, """{"error":"empty_body","route":"$PUT_ROUTE"}""")
                try {
                    val cid = store.put(bytes)
                    json(
                        200,
                        "{\"ok\":true,\"cid\":${jsonString(cid.value)},\"hex\":${jsonString(cid.hex)}," +
                            "\"path\":${jsonString(store.pathFor(cid))},\"casRoot\":${jsonString(casRoot)}," +
                            "\"bytes\":${bytes.size},\"store\":${jsonString(store::class.java.name)}}",
                    )
                } catch (t: Throwable) {
                    json(500, "{\"ok\":false,\"error\":${jsonString(t.toString())}}")
                }
            }

            bare == MATERIALIZE_ROUTE && method == "POST" -> {
                val body = bodyOf(payload).toString(StandardCharsets.UTF_8)
                val cidStr = field(body, "cid") ?: query(path, "cid")
                val topic = field(body, "topic") ?: query(path, "topic") ?: ""
                val newPath = field(body, "path") ?: query(path, "path")
                if (cidStr.isNullOrEmpty() || newPath.isNullOrEmpty()) {
                    return json(400, """{"error":"cid_and_path_required","route":"$MATERIALIZE_ROUTE"}""")
                }
                val hex = cidStr.substringAfter("sha256:", cidStr)
                if (hex.length != 64) return json(400, """{"error":"bad_cid","cid":${jsonString(cidStr)}}""")
                val cid = ContentId("sha256:$hex")
                val target = store.materializePathFor(topic, newPath)
                try {
                    val ok = store.reflinkReorganize(cid, topic, newPath)
                    json(
                        if (ok) 200 else 500,
                        "{\"ok\":$ok,\"primitive\":\"reflinkReorganize\",\"cid\":${jsonString(cid.value)}," +
                            "\"source\":${jsonString(store.pathFor(cid))},\"target\":${jsonString(target)}," +
                            "\"topic\":${jsonString(topic)}" +
                            (if (ok) "" else ",\"error\":${jsonString(store.lastReflinkError ?: "<none>")}") + "}",
                    )
                } catch (t: Throwable) {
                    json(500, "{\"ok\":false,\"error\":${jsonString(t.toString())}}")
                }
            }

            bare == GET_ROUTE && method == "GET" -> {
                val cidStr = query(path, "cid") ?: return json(400, """{"error":"cid_required"}""")
                val hex = cidStr.substringAfter("sha256:", cidStr)
                if (hex.length != 64) return json(400, """{"error":"bad_cid","cid":${jsonString(cidStr)}}""")
                val bytes = try {
                    store.get(ContentId("sha256:$hex"))
                } catch (t: Throwable) {
                    return json(500, "{\"ok\":false,\"error\":${jsonString(t.toString())}}")
                } ?: return json(404, """{"error":"not_found","cid":${jsonString(cidStr)}}""")
                JvmKanbanServer.HttpResponse(200, "", "application/octet-stream", bytes)
            }

            else -> json(405, """{"error":"method_not_allowed","method":"$method","path":"$bare"}""")
        }
    }
}
