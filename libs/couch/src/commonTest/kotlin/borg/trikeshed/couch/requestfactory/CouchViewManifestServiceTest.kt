package borg.trikeshed.couch.requestfactory

import borg.trikeshed.couch.relaxfactory.couchViewManifest
import borg.trikeshed.couch.relaxfactory.CouchViewInvocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CouchViewManifestServiceTest {

    private val fakeViewResponse = """
        {"total_rows":2,"offset":0,"rows":[
          {"id":"kline-1","key":"BTCUSDT","value":{"symbol":"BTCUSDT","open":50000}},
          {"id":"kline-2","key":"BTCUSDT","value":{"symbol":"BTCUSDT","open":51000}}
        ]}
    """.trimIndent()

    private val manifest = couchViewManifest("klinedb", "_design/kline") {
        view(
            name = "bySymbol",
            map = "function(doc){if(doc.symbol)emit(doc.symbol,doc)}",
            template = "_design/kline/_view/bySymbol?key=%1\$s",
            returnShape = CouchViewInvocation.ReturnShape.ListValue,
        )
        view(
            name = "byDate",
            map = "function(doc){emit([doc.year,doc.month,doc.day],doc)}",
            template = "_design/kline/_view/byDate?startkey=%1\$s&endkey=%2\$s",
            returnShape = CouchViewInvocation.ReturnShape.ListValue,
        )
    }

    @Test
    fun dispatchKnownView_returnsRowSet() {
        val capturedPath = mutableListOf<String>()
        val service = CouchViewManifestService(manifest) { path ->
            capturedPath += path
            fakeViewResponse
        }

        val call = RequestFactoryCall(
            context = "klinedb",
            method = "bySymbol",
            arguments = listOf(TransportValue.StringValue("BTCUSDT")),
        )
        val response = service.invoke(call)

        assertTrue(response.success)
        val rows = (response.value as TransportValue.ObjectValue)
            .values["rows"] as TransportValue.ArrayValue
        assertEquals(2, rows.values.size)

        // path should be rooted in the database and contain the encoded key
        val path = capturedPath.single()
        assertTrue(path.startsWith("/klinedb/"), "expected path starting with /klinedb/, got $path")
        assertTrue(path.contains("BTCUSDT"), "expected encoded symbol in path, got $path")
    }

    @Test
    fun dispatchUnknownView_returnsFailure() {
        val service = CouchViewManifestService(manifest) { _ -> error("should not call httpGet") }

        val call = RequestFactoryCall(
            context = "klinedb",
            method = "noSuchView",
            arguments = emptyList(),
        )
        val response = service.invoke(call)

        assertTrue(!response.success)
        val msg = (response.value as TransportValue.StringValue).value
        assertTrue(msg.contains("noSuchView"), "expected method name in error, got $msg")
    }

    @Test
    fun dispatchTwoArgView_buildsCorrectPath() {
        val capturedPath = mutableListOf<String>()
        val service = CouchViewManifestService(manifest) { path ->
            capturedPath += path
            fakeViewResponse
        }

        val call = RequestFactoryCall(
            context = "klinedb",
            method = "byDate",
            arguments = listOf(
                TransportValue.ArrayValue(listOf(TransportValue.IntegerValue(2024), TransportValue.IntegerValue(1), TransportValue.IntegerValue(1))),
                TransportValue.ArrayValue(listOf(TransportValue.IntegerValue(2024), TransportValue.IntegerValue(12), TransportValue.IntegerValue(31))),
            ),
        )
        val response = service.invoke(call)

        assertTrue(response.success)
        val path = capturedPath.single()
        assertTrue(path.contains("startkey="), "expected startkey in path, got $path")
        assertTrue(path.contains("endkey="), "expected endkey in path, got $path")
    }
}
