package borg.trikeshed.reactor

import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Nonce
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.nuid
import borg.trikeshed.lib.j
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.ArrayBuffer

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
                    // Convert Node.js Buffer to ByteArray
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
                            val action = decodeAction(body)
                            val result = localReactor.invoke(action)
                            val resultBody = encodeResult(result)
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

    private fun bytesToBase64(bytes: ByteArray): String {
        return js("Buffer.from(bytes).toString('base64')") as String
    }

    private fun base64ToBytes(base64: String): ByteArray {
        val buffer = js("Buffer.from(base64, 'base64')")
        val uint8Array = js("new Uint8Array(buffer)")
        val arrBuffer = uint8Array.buffer as ArrayBuffer
        return Int8Array(arrBuffer).unsafeCast<ByteArray>()
    }

    private fun encodeResult(result: ReactorResult): ByteArray {
        val nuid = result.a
        val cap = nuid.a
        val nonce = nuid.b.a
        val subnet = nuid.b.b

        val capCat = cap.category
        val capToken = if (cap is Capability.Custom) "${cap.kind}:${cap.token}" else if (cap is Capability.Process) cap.name else if (cap is Capability.Cas) cap.mode else if (cap is Capability.Wireproto) cap.route else ""

        val nonceBase64 = bytesToBase64(nonce.bytes)
        val derivedKey = if (nonce is Nonce.Derived) ",\"nonceDerivedKey\":\"derived\"" else ""
        val capTokenStr = if (capToken.isNotEmpty()) ",\"capabilityToken\":\"$capToken\"" else ""
        val verb = result.b.a
        val payload = bytesToBase64(result.b.b)

        val json = """{"nuid":{"capabilityCat":"$capCat"$capTokenStr,"nonceBytes":"$nonceBase64"$derivedKey,"subnet":"$subnet"},"verb":"$verb","payload":"$payload"}"""

        return json.encodeToByteArray()
    }

    private fun decodeAction(bytes: ByteArray): ReactorAction {
        val jsonStr = bytes.decodeToString()
        val obj = kotlin.js.JSON.parse<dynamic>(jsonStr)

        val nuidObj = obj.nuid
        val capabilityCat = nuidObj.capabilityCat as String
        val capabilityToken = nuidObj.capabilityToken as? String
        val nonceBytesStr = nuidObj.nonceBytes as String
        val nonceBytes = base64ToBytes(nonceBytesStr)
        val nonceDerivedKey = nuidObj.nonceDerivedKey as? String
        val subnetStr = nuidObj.subnet as String

        val cap = when (capabilityCat) {
            "process" -> Capability.Process(capabilityToken ?: "")
            "cas" -> Capability.Cas(capabilityToken ?: "")
            "wireproto" -> Capability.Wireproto(capabilityToken ?: "")
            "sctp" -> Capability.Sctp
            "modelmux" -> Capability.Model
            "blackboard" -> Capability.BlackBoard
            "custom" -> {
                val parts = (capabilityToken ?: ":").split(":", limit = 2)
                Capability.Custom(parts[0], parts.getOrElse(1) { "" })
            }
            else -> Capability.Custom(capabilityCat, capabilityToken ?: "")
        }

        val nonce = if (nonceDerivedKey != null) {
            Nonce.Derived(nonceDerivedKey)
        } else {
            Nonce.Restored(nonceBytes)
        }

        val parsedSubnet = Subnet.parse(subnetStr)
        val nuid = nuid(cap, nonce, parsedSubnet)

        val verb = obj.verb as String
        val payload = base64ToBytes(obj.payload as String)

        return nuid j (verb j payload)
    }
}
