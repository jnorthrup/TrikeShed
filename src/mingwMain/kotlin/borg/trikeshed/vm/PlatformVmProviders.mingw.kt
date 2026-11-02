package borg.trikeshed.vm

/** Windows: no VM tier yet (the process tier needs CreateProcess); the supervisor binds VmHost.NONE, as on macOS. */
actual fun platformVmProviders(): List<VmProvider> = emptyList()
