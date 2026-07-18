package borg.trikeshed.util.oroboros

import borg.trikeshed.lib.OpK

/**
 * Top-level facet union for the Oroboros coordinator subsystem.
 * Exposes access to Storage, Version, and Network lanes.
 */
sealed class OroborosK<out R> : OpK<R>() {
    object Storage : OroborosK<OroborosStorageRow>()
    object Version : OroborosK<VersionGateway>()
    object Network : OroborosK<OroborosNetworkRow>()
}
