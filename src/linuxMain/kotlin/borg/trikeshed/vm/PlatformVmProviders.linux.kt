package borg.trikeshed.vm

/**
 * linux: no VM tier is provided yet — process tier needs an interactive ProcessPipe (ProcessWorker is run-to-completion) — next ratchet. The supervisor binds [VmHost.NONE]
 * and the host view reports `vm.spawn` dead on this target (interface chokepoint, not an exclusion).
 */
actual fun platformVmProviders(): List<VmProvider> = emptyList()
