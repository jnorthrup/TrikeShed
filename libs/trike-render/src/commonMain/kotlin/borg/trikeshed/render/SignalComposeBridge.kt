package borg.trikeshed.render

import androidx.compose.runtime.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

// ── Signal<T> → Compose State<T> ──────────────────────────────
//
// Bridge between the window-toolkit Signal<T> reactive primitive
// and Compose's State<T>. A Signal is a hot observable (current
// value + changes Flow); Compose State is the recomposition trigger.
//
// This adapter lets window-toolkit components drive Compose UI
// without the toolkit knowing anything about Compose.

/**
 * Observe a Flow<T> as Compose [State<T>].
 *
 * Collects the flow in a LaunchedEffect and updates state on emission.
 * This is the generic bridge — works with any Flow, including
 * Signal<T>.changes from window-toolkit.
 */
@Composable
fun <T> Flow<T>.asState(initial: T): State<T> {
    val state = remember { mutableStateOf(initial) }
    LaunchedEffect(this) {
        collectLatest { state.value = it }
    }
    return state
}

/**
 * Observe a Flow<T> as Compose [State<T>] with a lazy initial value.
 */
@Composable
fun <T : R, R> Flow<T>.asState(initial: R, started: LazyThreadSafetyMode): State<R> {
    val state = remember { mutableStateOf(initial) }
    LaunchedEffect(this) {
        collectLatest { state.value = it }
    }
    return state
}
