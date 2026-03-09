package borg.trikeshed.ccek

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext

/** Retrieve a keyed service from the current coroutine context, or null if absent. */
suspend fun <T : KeyedService> coroutineService(key: CoroutineContext.Key<T>): T? =
    currentCoroutineContext()[key]
