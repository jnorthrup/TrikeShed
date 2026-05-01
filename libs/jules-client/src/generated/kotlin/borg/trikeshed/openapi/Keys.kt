package borg.trikeshed.openapi

/**
 * Generated from /Users/jim/work/TrikeShed/libs/jules-client/openapi/jules.openapi.yaml
 * by ./gradlew generateJulesSources.
 * Repository policy: this checked-in file must be regenerated, not edited by hand.
 */

import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.htx.client.HtxKey

object Keys {
    val htx: AsyncContextKey<HtxElement> = HtxKey
}