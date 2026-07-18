package borg.trikeshed.litebike.tunnel.gates

import borg.trikeshed.litebike.taxonomy.WamBlock
import borg.trikeshed.context.nuid.Nuid

/**
 * Gate interface port from litebike.
 * Evaluates whether a given block of protocol state combined with a
 * particular Nuid (identity/capability) is permitted to pass.
 */
interface Gate {
    fun isPermitted(block: WamBlock, nuid: Nuid): Boolean
}

/**
 * Basic AllowAllGate that allows everything.
 */
object AllowAllGate : Gate {
    override fun isPermitted(block: WamBlock, nuid: Nuid): Boolean {
        return true
    }
}
