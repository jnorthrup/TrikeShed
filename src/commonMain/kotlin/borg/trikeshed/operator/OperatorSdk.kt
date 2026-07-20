package borg.trikeshed.operator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

sealed class K8sEvent<T> {
    data class Added<T>(val resource: T) : K8sEvent<T>()
    data class Modified<T>(val oldResource: T, val newResource: T) : K8sEvent<T>()
    data class Deleted<T>(val resource: T) : K8sEvent<T>()
}

interface Reconciler<T> {
    suspend fun reconcile(event: K8sEvent<T>)
}

interface OperatorSdk<T> {
    val events: Flow<K8sEvent<T>>
    suspend fun start()
    suspend fun stop()
    fun registerReconciler(reconciler: Reconciler<T>)
    suspend fun dispatchEvent(event: K8sEvent<T>)
}

open class BaseOperatorSdk<T>(private val scope: CoroutineScope) : OperatorSdk<T> {
    private val _events = MutableSharedFlow<K8sEvent<T>>(replay = 64, extraBufferCapacity = 64)
    override val events: Flow<K8sEvent<T>> = _events.asSharedFlow()
    private val reconcilers = mutableListOf<Reconciler<T>>()

    override suspend fun start() {
        scope.launch {
            _events.collect { event ->
                reconcilers.forEach { it.reconcile(event) }
            }
        }
    }

    override suspend fun stop() {
    }

    override fun registerReconciler(reconciler: Reconciler<T>) {
        reconcilers.add(reconciler)
    }

    override suspend fun dispatchEvent(event: K8sEvent<T>) {
        _events.emit(event)
    }
}
