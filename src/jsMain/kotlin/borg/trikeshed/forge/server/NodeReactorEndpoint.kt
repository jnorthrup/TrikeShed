package borg.trikeshed.forge.server

import borg.trikeshed.reactor.ReactorAction
import borg.trikeshed.reactor.ReactorEndpoint
import borg.trikeshed.reactor.ReactorResult
import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Nonce
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.nuid
import borg.trikeshed.lib.j
import kotlinx.browser.window

// Polyfill atob and btoa for encoding/decoding in JS
external fun btoa(s: String): String
external fun atob(s: String): String

class NodeReactorEndpoint(
    private val baseUrl: String,        // e.g., "http://localhost:8088"
) : ReactorEndpoint {
    override suspend fun invoke(action: ReactorAction): ReactorResult {
        val spec = HttpForwarderSpec(
            verb = "POST",
            path = "/api/invoke",
            headers = mapOf("Content-Type" to "application/octet-stream"),
            body = encodeAction(action),
        )
        val response = NodeHttpForwarder.send(baseUrl, spec)
        if (response.status != 200) throw RuntimeException("reactor returned ${response.status}")
        return decodeResult(response.body)
    }

    private fun bytesToBase64(bytes: ByteArray): String {
        return window.btoa(bytes.decodeToString())
    }

    private fun base64ToBytes(base64: String): ByteArray {
        return window.atob(base64).encodeToByteArray()
    }

    private fun encodeAction(action: ReactorAction): ByteArray {
        val nuid = action.a
        val cap = nuid.a
        val nonce = nuid.b.a
        val subnet = nuid.b.b

        val capCat = cap.category
        val capToken = if (cap is Capability.Custom) "${cap.kind}:${cap.token}" else if (cap is Capability.Process) cap.name else if (cap is Capability.Cas) cap.mode else if (cap is Capability.Wireproto) cap.route else ""

        // Hand-roll JSON using strings to avoid kotlinx.serialization.json dependency which isn't in commonMain/jsMain
        val nonceBase64 = bytesToBase64(nonce.bytes)
        val derivedKey = if (nonce is Nonce.Derived) ",\"nonceDerivedKey\":\"derived\"" else ""
        val capTokenStr = if (capToken.isNotEmpty()) ",\"capabilityToken\":\"$capToken\"" else ""
        val verb = action.b.a
        val payload = bytesToBase64(action.b.b)

        val json = """{"nuid":{"capabilityCat":"$capCat"$capTokenStr,"nonceBytes":"$nonceBase64"$derivedKey,"subnet":"$subnet"},"verb":"$verb","payload":"$payload"}"""

        return json.encodeToByteArray()
    }

    // Quick and dirty manual JSON parser using JS interop to avoid library deps
    private fun decodeResult(bytes: ByteArray): ReactorResult {
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
