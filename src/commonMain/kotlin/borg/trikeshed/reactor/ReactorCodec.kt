package borg.trikeshed.reactor

import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Nonce
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.nuid
import borg.trikeshed.lib.j
import kotlinx.serialization.Serializable

@Serializable
data class ConfixReactorEnvelope(
    val capabilityCat: String,
    val capabilityToken: String? = null,
    val nonceBytes: ByteArray,
    val nonceDerivedKey: String? = null,
    val subnet: String,
    val verb: String,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ConfixReactorEnvelope

        if (capabilityCat != other.capabilityCat) return false
        if (capabilityToken != other.capabilityToken) return false
        if (!nonceBytes.contentEquals(other.nonceBytes)) return false
        if (nonceDerivedKey != other.nonceDerivedKey) return false
        if (subnet != other.subnet) return false
        if (verb != other.verb) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = capabilityCat.hashCode()
        result = 31 * result + (capabilityToken?.hashCode() ?: 0)
        result = 31 * result + nonceBytes.contentHashCode()
        result = 31 * result + (nonceDerivedKey?.hashCode() ?: 0)
        result = 31 * result + subnet.hashCode()
        result = 31 * result + verb.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

fun ReactorAction.toConfixEnvelope(): ConfixReactorEnvelope {
    val nuid = this.a
    val cap = nuid.a
    val nonce = nuid.b.a
    val subnet = nuid.b.b
    val verb = this.b.a
    val payload = this.b.b

    val capCat = cap.category
    val capToken = if (cap is Capability.Custom) "${cap.kind}:${cap.token}" else if (cap is Capability.Process) cap.name else if (cap is Capability.Cas) cap.mode else if (cap is Capability.Wireproto) cap.route else null
    val nonceDerived = if (nonce is Nonce.Derived) "derived" else null

    return ConfixReactorEnvelope(
        capabilityCat = capCat,
        capabilityToken = capToken,
        nonceBytes = nonce.bytes,
        nonceDerivedKey = nonceDerived,
        subnet = subnet.toString(),
        verb = verb,
        payload = payload
    )
}

fun ConfixReactorEnvelope.toReactorAction(): ReactorAction {
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

    val parsedSubnet = Subnet.parse(subnet)
    val nuid = nuid(cap, nonce, parsedSubnet)

    return nuid j (verb j payload)
}
