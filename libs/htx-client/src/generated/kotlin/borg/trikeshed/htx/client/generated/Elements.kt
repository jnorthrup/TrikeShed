package borg.trikeshed.htx.client.generated

/**
 * Generated from ../server/openapi/htx-general.openapi.yaml by ./gradlew -p libs/htx-client openApiGenerateHtxGeneralClient.
 * Repository policy: this checked-in file must be regenerated, not edited by hand.
 */

import borg.trikeshed.htx.client.HtxElement
import borg.trikeshed.htx.client.openHtxElement as openHtxElementRuntime

object Elements {
    suspend fun htx(): HtxElement = openHtxElementRuntime()
}
