package borg.trikeshed.reactor

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array

class NodeLocalServer(
    private val port: Int,
    private val localReactor: ReactorEndpoint
) {
    private var server: dynamic = null

    @OptIn(DelicateCoroutinesApi::class)
    fun start() {
        val http = js("require('http')")
        server = http.createServer { req: dynamic, res: dynamic ->
            if (req.method == "POST" && req.url == "/api/invoke") {
                val chunks = mutableListOf<ByteArray>()
                req.on("data") { chunk: dynamic ->
                    val uint8Array = js("new Uint8Array(chunk)")
                    val buffer = uint8Array.buffer as ArrayBuffer
                    val bytes = Int8Array(buffer).unsafeCast<ByteArray>()
                    chunks.add(bytes)
                }
                req.on("end") { ->
                    val totalSize = chunks.sumOf { it.size }
                    val body = ByteArray(totalSize)
                    var offset = 0
                    for (chunk in chunks) {
                        chunk.copyInto(body, offset)
                        offset += chunk.size
                    }

                    GlobalScope.launch {
                        try {
                            val action = ReactorJsonCodec.decode(body)
                            val result = localReactor.invoke(action)
                            val resultBody = ReactorJsonCodec.encode(result)
                            res.writeHead(200, js("{'Content-Type': 'application/octet-stream'}"))
                            res.end(js("Buffer.from(resultBody)"))
                        } catch (e: Throwable) {
                            res.writeHead(500, js("{'Content-Type': 'text/plain'}"))
                            res.end(e.message ?: "Unknown Error")
                        }
                    }
                }
            } else {
                res.writeHead(404)
                res.end("Not Found")
            }
        }
        server.listen(port)
    }

    fun stop() {
        server?.close()
    }
}
