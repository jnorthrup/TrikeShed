package borg.trikeshed.ccek

import kotlin.coroutines.CoroutineContext

/** Base marker for all CCEK keyed services. */
interface KeyedService : CoroutineContext.Element
