package borg.trikeshed.server.generated

import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

object SupervisorJobs {
    fun getHealth(parent: Job? = null): Job = SupervisorJob(parent)
}
