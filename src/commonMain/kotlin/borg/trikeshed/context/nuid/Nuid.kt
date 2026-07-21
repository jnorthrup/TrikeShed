package borg.trikeshed.context.nuid

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j

data class Nuid(
    override val a: Capability,
    val nonce: Nonce,
    val subnet: Subnet
) : Join<Capability, Join<Nonce, Subnet>> {
    override val b: Join<Nonce, Subnet>
        get() = nonce j subnet
}
