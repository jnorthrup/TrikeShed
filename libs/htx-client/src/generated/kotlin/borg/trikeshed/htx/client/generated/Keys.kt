package borg.trikeshed.htx.client.generated

/**
 * Generated from ../server/openapi/htx-general.openapi.yaml by ./gradlew -p libs/htx-client openApiGenerateHtxGeneralClient.
 * Repository policy: this checked-in file must be regenerated, not edited by hand.
 */

import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.htx.client.HtxKey
import borg.trikeshed.htx.client.HtxElement

object Keys {
    val htx: AsyncContextKey<HtxElement> = HtxKey
    const val operationId: String = "getHealth"
}
