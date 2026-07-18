package borg.trikeshed.util.oroboros

import borg.trikeshed.lib.FacetedRow
import borg.trikeshed.lib.get

/**
 * Exposes Storage, Version, and Network lanes for Oroboros.
 */
interface OroborosGateway : FacetedRow<OroborosK<*>> {
    val storage: OroborosStorageRow get() = this.b(OroborosK.Storage) as OroborosStorageRow
    val version: VersionGateway get() = this.b(OroborosK.Version) as VersionGateway
    val network: OroborosNetworkRow get() = this.b(OroborosK.Network) as OroborosNetworkRow
}
