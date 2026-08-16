package modelmux

import borg.trikeshed.couch.*
import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.reactor.MuxReactorElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.lib.*
import borg.trikeshed.cursor.*

@Serializable
data class QuotaSnapshot(
    val provider: String,
    val windowStart: Long,
    val quotaRemaining: Double,
    val spent: Double
)

class QuotaTelemetry(
    private val couchStore: CouchStore
) {
    suspend fun recordSnapshot(snapshot: QuotaSnapshot): Boolean {
        require(currentCoroutineContext()[MuxReactorElement.Key] != null) { 
            "write API is reactor-internal" 
        }
        
        val jsonString = JsonSupport.stringify(snapshot)
        val cid = ContentId.of(jsonString.encodeToByteArray())
        
        val fields = listOf(
            Field("provider", snapshot.provider),
            Field("windowStart", snapshot.windowStart),
            Field("quotaRemaining", snapshot.quotaRemaining),
            Field("spent", snapshot.spent),
            Field("type", "QuotaSnapshot")
        )
        
        val doc = Document(id = cid.hex, fields = fields)
        return couchStore.put(doc)
    }

    fun readLatest(provider: String): QuotaSnapshot? {
        val queryResult = couchStore.query("type", "QuotaSnapshot")
        var latest: QuotaSnapshot? = null
        
        val cursor: Series<RowVec> = queryResult.cursor
        for (i in 0 until cursor.size) {
            val row = cursor[i]
            var p = ""
            var ws = 0L
            var qr = 0.0
            var s = 0.0
            for (colIdx in 0 until row.size) {
                val cell = row.b(colIdx)
                val name = cell.b().name.toString()
                val colVal = cell.a
                when (name) {
                    "provider" -> p = colVal as String
                    "windowStart" -> ws = (colVal as Number).toLong()
                    "quotaRemaining" -> qr = (colVal as Number).toDouble()
                    "spent" -> s = (colVal as Number).toDouble()
                }
            }
            if (p == provider) {
                if (latest == null || ws > latest.windowStart) {
                    latest = QuotaSnapshot(p, ws, qr, s)
                }
            }
        }
        
        return latest
    }
}
