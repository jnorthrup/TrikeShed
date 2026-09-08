package borg.trikeshed.platform

/** JVM properties describe the running VM's ABI, including under CPU translation. */
internal fun jvmHostDescriptor(property: (String) -> String? = System::getProperty): HostDescriptor {
    fun value(name: String): String? = try {
        property(name)?.takeIf { it.isNotBlank() }
    } catch (_: SecurityException) {
        null
    }

    return HostDescriptor(
        runtime = HostRuntime.JVM,
        rawOs = value("os.name"),
        rawArchitecture = value("os.arch"),
        osVersion = value("os.version"),
        runtimeVersion = value("java.version"),
        runtimeName = value("java.vm.name"),
        runtimeVendor = value("java.vendor"),
        vmVersion = value("java.vm.version"),
        jvmSpecificationVersion = value("java.specification.version"),
        // Standard JVM properties expose neither CPU branding nor the supported JNI ABI.
        // java.vendor identifies the runtime distributor, not the CPU manufacturer.
    )
}
