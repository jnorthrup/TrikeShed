package borg.trikeshed.forge.server

import borg.trikeshed.reactor.ReactorAction
import borg.trikeshed.reactor.ReactorEndpoint
import borg.trikeshed.reactor.ReactorResult
import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Nonce
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.nuid
import borg.trikeshed.lib.j
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class LoopbackReactorHandler(
    private val port: Int = 8088,
    private val endpoint: ReactorEndpoint,
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(port), 0)

    val serverPort: Int
        get() = server.address.port

    init {
        server.createContext("/api/invoke") { exchange ->
            if (exchange.requestMethod == "POST") {
                val body = exchange.requestBody.readBytes()
                val action = decodeAction(body)
                val result = runBlocking { endpoint.invoke(action) }
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { os ->
                    os.write(encodeResult(result))
                }
            } else {
                exchange.sendResponseHeaders(405, 0)
                exchange.close()
            }
        }
        server.executor = Executors.newFixedThreadPool(4)
        server.start()
    }

    override fun close() { server.stop(0) }

    companion object {
        fun encodeAction(action: ReactorAction): ByteArray {
            val nuid = action.a
            val cap = nuid.a
            val nonce = nuid.b.a
            val subnet = nuid.b.b

            val capCat = cap.category
            val capToken = if (cap is Capability.Custom) "${cap.kind}:${cap.token}" else if (cap is Capability.Process) cap.name else if (cap is Capability.Cas) cap.mode else if (cap is Capability.Wireproto) cap.route else null

            val json = buildJsonObject {
                put("nuid", buildJsonObject {
                    put("capabilityCat", capCat)
                    if (capToken != null) put("capabilityToken", capToken)
                    put("nonceBytes", Base64.getEncoder().encodeToString(nonce.bytes))
                    if (nonce is Nonce.Derived) put("nonceDerivedKey", "derived")
                    put("subnet", subnet.toString())
                })
                put("verb", action.b.a)
                put("payload", Base64.getEncoder().encodeToString(action.b.b))
            }.toString()
            return json.encodeToByteArray()
        }

        fun decodeAction(bytes: ByteArray): ReactorAction {
            val obj = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            val nuidObj = obj["nuid"]!!.jsonObject
            val capabilityCat = nuidObj["capabilityCat"]!!.jsonPrimitive.content
            val capabilityToken = nuidObj["capabilityToken"]?.jsonPrimitive?.content
            val nonceBytesStr = nuidObj["nonceBytes"]!!.jsonPrimitive.content
            val nonceBytes = Base64.getDecoder().decode(nonceBytesStr)
            val nonceDerivedKey = nuidObj["nonceDerivedKey"]?.jsonPrimitive?.content
            val subnetStr = nuidObj["subnet"]!!.jsonPrimitive.content

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

            val verb = obj["verb"]!!.jsonPrimitive.content
            val payload = Base64.getDecoder().decode(obj["payload"]!!.jsonPrimitive.content)

            return nuid j (verb j payload)
        }

        fun encodeResult(result: ReactorResult): ByteArray {
            // ReactorResult is the exact same typealias as ReactorAction
            return encodeAction(result)
        }

        fun decodeResult(bytes: ByteArray): ReactorResult {
             // ReactorResult is the exact same typealias as ReactorAction
            return decodeAction(bytes)
        }
    }
}
