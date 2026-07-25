/*
 * Copyright (c) 2024 TrikeShed Authors
 * This file is part of TrikeShed, released under the AGPLv3 license.
 */

package borg.trikeshed.wireproto

import borg.trikeshed.reactor.ReactorEndpoint
import borg.trikeshed.reactor.ReactorAction
import borg.trikeshed.reactor.ReactorResult
import borg.trikeshed.lib.j
import borg.trikeshed.collections.associative.Cbor
import borg.trikeshed.collections.associative.Item
import borg.trikeshed.collections.associative.itemArrayOf
import borg.trikeshed.context.nuid.Capability
import borg.trikeshed.context.nuid.Nonce
import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.context.nuid.nuid

class ConfixWorker : ReactorEndpoint {
    override suspend fun invoke(action: ReactorAction): ReactorResult {
        // Serialize ReactorAction to CBOR
        val encodedBytes = serialize(action)
        
        // Deserialize CBOR to ReactorAction
        val decodedAction = deserialize(encodedBytes)
        
        return decodedAction
    }

    private fun serialize(action: ReactorAction): ByteArray {
        val nuid = action.a
        val verb = action.b.a
        val payload = action.b.b

        val capStr = serializeCapability(nuid.a)
        val nonceStr = serializeNonce(nuid.b.a)
        val subnetStr = nuid.b.b.toString()

        val item = itemArrayOf(
            Item.Str(capStr),
            Item.Str(nonceStr),
            Item.Str(subnetStr),
            Item.Str(verb),
            Item.Bin(payload)
        )
        return Cbor.encode(item)
    }

    private fun deserialize(bytes: ByteArray): ReactorAction {
        val item = Cbor.decode(bytes) as Item.Arr
        
        val capStr = (item.items.b(0) as Item.Str).value
        val nonceStr = (item.items.b(1) as Item.Str).value
        val subnetStr = (item.items.b(2) as Item.Str).value
        val verb = (item.items.b(3) as Item.Str).value
        val payload = (item.items.b(4) as Item.Bin).value

        val cap = deserializeCapability(capStr)
        val nonce = deserializeNonce(nonceStr)
        val subnet = Subnet.parse(subnetStr)

        val decodedNuid = nuid(cap, nonce, subnet)
        return decodedNuid j (verb j payload)
    }
    
    private fun serializeCapability(cap: Capability): String {
        return when (cap) {
            is Capability.Process -> "process:" + cap.name
            is Capability.Cas -> "cas:" + cap.mode
            is Capability.Wireproto -> "wireproto:" + cap.route
            is Capability.Custom -> "custom:" + cap.kind + ":" + cap.token
            is Capability.Sctp -> "sctp:"
            is Capability.Model -> "modelmux:"
            is Capability.BlackBoard -> "blackboard:"
            else -> cap.category + ":"
        }
    }

    private fun deserializeCapability(str: String): Capability {
        val parts = str.split(":", limit = 3)
        val cat = parts[0]
        val arg = if (parts.size > 1) parts[1] else ""
        return when (cat) {
            "process" -> Capability.Process(arg)
            "cas" -> Capability.Cas(arg)
            "wireproto" -> Capability.Wireproto(arg)
            "custom" -> Capability.Custom(arg, if (parts.size > 2) parts[2] else "")
            "sctp" -> Capability.Sctp
            "modelmux" -> Capability.Model
            "blackboard" -> Capability.BlackBoard
            else -> Capability.Custom(cat, arg)
        }
    }

    private fun serializeNonce(nonce: Nonce): String {
        val bytesStr = nonce.bytes.joinToString(",")
        return when (nonce) {
            is Nonce.Derived -> "derived:" + bytesStr
            else -> "restored:" + bytesStr
        }
    }

    private fun deserializeNonce(str: String): Nonce {
        val parts = str.split(":", limit = 2)
        val type = parts[0]
        val bytesStr = parts.getOrNull(1) ?: ""
        val bytes = if (bytesStr.isEmpty()) ByteArray(0) else bytesStr.split(",").map { it.toByte() }.toByteArray()
        return Nonce.Restored(bytes)
    }
}
