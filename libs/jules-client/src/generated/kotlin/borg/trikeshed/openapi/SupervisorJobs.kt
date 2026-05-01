package borg.trikeshed.openapi

/**
 * Generated from /Users/jim/work/TrikeShed/libs/jules-client/openapi/jules.openapi.yaml
 * by ./gradlew generateJulesSources.
 * Repository policy: this checked-in file must be regenerated, not edited by hand.
 */

import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

object SupervisorJobs {
    fun createSession(parent: Job? = null): Job = SupervisorJob(parent)
}