package borg.trikeshed.browser

import borg.trikeshed.job.JobCommand
import kotlinx.coroutines.channels.Channel

fun boundedIngress(capacity: Int = 64): Channel<JobCommand> {
    return Channel(capacity)
}
