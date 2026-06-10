@file:Suppress("NonAsciiCharacters", "UNCHECKED_CAST", "NAME_SHADOWING")

package borg.trikeshed.couch.htx

import borg.trikeshed.cursor.*
import borg.trikeshed.lib.*
import borg.trikeshed.parse.confix.*
import borg.trikeshed.userspace.ByteRegion
import borg.trikeshed.userspace.network.*

// ─────────────────────────────────────────────────────────────────────────────
// HTX COUCH TRANSPORT — CouchDB REST API over HTX
// ─────────────────────────────────────────────────────────────────────────────

enum class CouchMethod {
    GET, PUT, POST, DELETE, COPY
}

data class CouchRequest(
    val method: CouchMethod,
    val db: String,
    val docId: String = "",
    val params: Map<String, String> = emptyMap(),
    val body: ConfixDoc? = null,
    val rev: String? = null,
) {
    fun path(): String = buildString {
        append("/")
        append(db)
        if (docId.isNotEmpty()) {
            append("/")
            append(docId)
        }
        if (params.isNotEmpty()) {
            append("?")
            params.forEach { (k, v) ->
                if (length > 0 && !endsWith("?")) append("&")
                append(k)
                append("=")
                append(v)
            }
        }
    }

    fun ifMatch(): String? = rev
}

data class CouchResponse(
    val status: Int,
    val reason: String = "",
    val doc: ConfixDoc? = null,
    val rev: String? = null,
    val id: String? = null,
    val error: String? = null,
    val ok: Boolean = status in 200..299,
) {
    val isOk: Boolean get() = ok
    fun errorMessage(): String? = error?.let { "$it: $reason" }
}

class HtxCouchTransport(
    val channel: Channel,
    val baseUrl: String = "http://localhost:5984",
) {
    suspend fun execute(request: CouchRequest): CouchResponse {
        val path = request.path()
        val methodStr = request.method.name
        val bodyBytes = request.body?.let { doc ->
            val sb = StringBuilder()
            doc.root?.let { row ->
                serializeRow(row, doc.src, sb)
            }
            sb.toString().encodeToByteArray()
        }
        return executeHttp(methodStr, path, bodyBytes)
    }

    private suspend fun executeHttp(
        method: String,
        path: String,
        body: ByteArray?,
    ): CouchResponse {
        // Stub - TODO: implement actual HTX channel I/O
        return CouchResponse(status = 200, reason = "OK", doc = null)
    }

    suspend fun get(db: String, id: String): CouchResponse =
        execute(CouchRequest(CouchMethod.GET, db, id))

    suspend fun put(db: String, id: String, doc: ConfixDoc, rev: String? = null): CouchResponse =
        execute(CouchRequest(CouchMethod.PUT, db, id, body = doc, rev = rev))

    suspend fun put(db: String, id: String, text: String, rev: String? = null): CouchResponse =
        put(db, id, confixDoc(text), rev)

    suspend fun delete(db: String, id: String, rev: String): CouchResponse =
        execute(CouchRequest(CouchMethod.DELETE, db, id, rev = rev))

    suspend fun copy(db: String, id: String, destId: String, rev: String): CouchResponse =
        execute(CouchRequest(CouchMethod.PUT, db, destId, params = mapOf("rev" to rev)))

    suspend fun allDbs(): CouchResponse =
        execute(CouchRequest(CouchMethod.GET, "_all_dbs"))

    suspend fun createDb(db: String): CouchResponse =
        execute(CouchRequest(CouchMethod.PUT, db, ""))

    suspend fun deleteDb(db: String): CouchResponse =
        execute(CouchRequest(CouchMethod.DELETE, db, ""))

    suspend fun dbInfo(db: String): CouchResponse =
        execute(CouchRequest(CouchMethod.GET, db, ""))

    suspend fun view(db: String, ddoc: String, view: String, params: Map<String, String> = emptyMap()): CouchResponse =
        execute(CouchRequest(CouchMethod.GET, db, "_design/$ddoc/_view/$view", params))

    suspend fun find(db: String, selector: ConfixDoc): CouchResponse =
        execute(CouchRequest(CouchMethod.POST, db, "_find", body = selector))

    suspend fun bulkDocs(db: String, docs: List<ConfixDoc>): CouchResponse =
        execute(CouchRequest(CouchMethod.POST, db, "_bulk_docs", body = docs.firstOrNull()))

    override fun toString(): String = "HtxCouchTransport(baseUrl=$baseUrl)"
}

object HtxTransportFactory {
    fun create(channel: Channel, baseUrl: String = "http://localhost:5984"): HtxCouchTransport =
        HtxCouchTransport(channel, baseUrl)

    fun connect(url: String): HtxCouchTransport {
        val baseUrl = url.removeSuffix("/")
        // Stub channel - TODO: create actual channel from URL
        return HtxCouchTransport(StubChannel(), baseUrl)
    }

    private class StubChannel : Channel {
        override fun channelType(): String = "http"
        override fun isConnected(): Boolean = false
        override fun metadata(): ChannelMetadata? = null
        override fun read(dst: ByteRegion): Int = -1
        override fun write(src: ByteSeries): Int = -1
    }
}

// Helper serialization
private fun serializeRow(row: RowVec, src: Series<Byte>, sb: StringBuilder) {
    val tag = row.tag
    when (tag) {
        IOMemento.IoObject -> {
            sb.append("{")
            val kids = row.kids
            var first = true
            for (i in 0 until kids.size step 2) {
                if (!first) sb.append(",")
                first = false
                val key = kids[i]
                val value = kids[i + 1]
                if (key.tag == IOMemento.IoString) {
                    val keyStr = src.spanStr(key.open + 1, key.close - 1)
                    sb.append("\"$keyStr\":")
                    serializeRow(value, src, sb)
                }
            }
            sb.append("}")
        }
        IOMemento.IoArray -> {
            sb.append("[")
            val kids = row.kids
            var first = true
            for (i in 0 until kids.size) {
                if (!first) sb.append(",")
                first = false
                serializeRow(kids[i], src, sb)
            }
            sb.append("]")
        }
        IOMemento.IoString -> {
            val str = src.spanStr(row.open + 1, row.close - 1)
            sb.append("\"$str\"")
        }
        IOMemento.IoInt -> {
            val n = src.spanLong(row.open, row.close)
            sb.append(n)
        }
        IOMemento.IoLong -> {
            val n = src.spanLong(row.open, row.close)
            sb.append(n)
        }
        IOMemento.IoDouble -> {
            val d = src.spanStr(row.open, row.close).toDoubleOrNull() ?: 0.0
            sb.append(d)
        }
        IOMemento.IoBoolean -> {
            val b = src[row.open].toInt().toChar() == 't'
            sb.append(b)
        }
        else -> sb.append("null")
    }
}

private fun Series<Byte>.spanStr(open: Int, close: Int): String {
    if (close < open) return ""
    val len = close - open + 1
    return CharArray(len) { this[open + it].toInt().toChar() }.concatToString()
}

private fun Series<Byte>.spanLong(open: Int, close: Int): Long {
    var v = 0L; var neg = false; var i = open
    if (i <= close && this[i].toInt().toChar() == '-') { neg = true; i++ }
    while (i <= close) { v = v * 10 + (this[i++].toInt().toChar() - '0') }
    return if (neg) -v else v
}
