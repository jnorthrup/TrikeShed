package borg.trikeshed.htx.client.generated

/**
 * Generated from ../server/openapi/htx-general.openapi.yaml by ./gradlew -p libs/htx-client openApiGenerateHtxGeneralClient.
 * Repository policy: this checked-in file must be regenerated, not edited by hand.
 */

import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

object SupervisorJobs {
    fun getHealth(parent: Job? = null): Job = SupervisorJob(parent)
}
