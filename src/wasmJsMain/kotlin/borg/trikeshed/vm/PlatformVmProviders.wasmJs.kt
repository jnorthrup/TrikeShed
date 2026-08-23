package borg.trikeshed.vm

/**
 * wasmJs: no VM tier is provided yet — browser Worker sandbox for wasmJs is a follow-up. The supervisor binds [VmHost.NONE]
 * and the host view reports `vm.spawn` dead on this target (interface chokepoint, not an exclusion).
 */
actual fun platformVmProviders(): List<VmProvider> = emptyList()
