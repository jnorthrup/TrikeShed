package borg.trikeshed.reactor

import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Nonce
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.nuid
import borg.trikeshed.lib.j
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array

/**
 * Shared JSON codec for the Reactor wire envelope on JS.
 *
 * Both NodeReactorEndpoint (client) and NodeLocalServer (server) were hand-
 * duplicating this serialization; clients that drift from servers break the
 * wire. Canonicalize here.
 */
object ReactorJsonCodec {

    private fun base64ToBytes(base64: String): ByteArray {
        val buffer = js("Buffer.from(base64, 'base64')")
        val uint8Array = js("new Uint8Array(buffer)")
        val arrBuffer = uint8Array.buffer as ArrayBuffer
        return Int8Array(arrBuffer).unsafeCast<ByteArray>()
    }

    fun encode(action: ReactorAction): ByteArray {
        val nuid = action.a
        val cap = nuid.a
        val nonce = nuid.b.a
        val subnet = nuid.b.b

        val capCat = cap.category
        val capToken = if (cap is Capability.Custom) "${cap.kind}:${cap.token}"
            else if (cap is Capability.Process) cap.name
            else if (cap is Capability.Cas) cap.mode
            else if (cap is Capability.Wireproto) cap.route
            else ""

        val nonceBytesArray = nonce.bytes
        val nonceBase64 = js("Buffer.from(nonceBytesArray).toString('base64')") as String
        val derivedKey = if (nonce is Nonce.Derived) ",\"nonceDerivedKey\":\"derived\"" else ""
        val capTokenStr = if (capToken.isNotEmpty()) ",\"capabilityToken\":\"$capToken\"" else ""
        val verb = action.b.a
        val payloadBytesArray = action.b.b
        val payload = js("Buffer.from(payloadBytesArray).toString('base64')") as String

        val json = """{"nuid":{"capabilityCat":"$capCat"$capTokenStr,"nonceBytes":"$nonceBase64"$derivedKey,"subnet":"$subnet"},"verb":"$verb","payload":"$payload"}"""
        return json.encodeToByteArray()
    }

    fun decode(bytes: ByteArray): ReactorResult {
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
