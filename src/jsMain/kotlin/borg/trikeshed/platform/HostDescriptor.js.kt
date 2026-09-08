package borg.trikeshed.platform

import kotlin.js.jsTypeOf

internal fun jsNodeProcess(process: dynamic): Boolean = try {
    jsString(process?.versions?.node) != null &&
        (process?.release?.name == null || process.release.name == "node")
} catch (_: dynamic) {
    false
}

/** Runtime API observations only: a Node identity does not imply native module access. */
internal fun jsHostDescriptor(
    process: dynamic,
    os: dynamic,
    browser: Boolean = false,
    hosted: Boolean = false,
): HostDescriptor {
    if (!jsNodeProcess(process)) return HostDescriptor(
        runtime = when {
            hosted -> HostRuntime.HOSTED_JS
            browser -> HostRuntime.BROWSER_JS
            else -> HostRuntime.JS
        },
    )
    val cpus = jsObservation { if (jsTypeOf(os?.cpus) == "function") os.cpus() else null }
    val length = jsObservation { (cpus?.length as? Number)?.toInt() } ?: 0
    val cpuModel = (0 until length).mapNotNull { index ->
        jsObservation { jsString(cpus[index]?.model) }
    }.distinct().joinToString("; ").ifEmpty { null }
    val rawOs = jsObservation { jsString(process.platform) }
    val glibc = if (rawOs == "linux") jsObservation {
        if (jsTypeOf(process.report?.getReport) == "function") {
            jsString(process.report.getReport()?.header?.glibcVersionRuntime)
        } else null
    } else null
    return HostDescriptor(
        runtime = HostRuntime.NODE_JS,
        rawOs = rawOs,
        rawArchitecture = jsObservation { jsString(process.arch) },
        osVersion = jsObservation { if (jsTypeOf(os?.release) == "function") jsString(os.release()) else null },
        cpuModel = cpuModel,
        runtimeVersion = jsObservation { jsString(process.versions.node) },
        runtimeName = jsObservation {
            if (jsString(process.versions.electron) != null) "electron" else jsString(process.release?.name)
        },
        vmVersion = jsObservation { jsString(process.versions.v8) },
        nativeAbi = jsObservation { jsString(process.versions.modules) },
        napiVersion = jsObservation { jsString(process.versions.napi)?.toIntOrNull() },
        libc = glibc?.let { "glibc" },
    )
}

internal fun jsHostProcessors(os: dynamic, navigator: dynamic): Int =
    jsObservation { (os?.availableParallelism?.call(os) as? Number)?.toInt()?.takeIf { it > 0 } }
        ?: jsObservation { (os?.cpus?.call(os)?.length as? Number)?.toInt()?.takeIf { it > 0 } }
        ?: jsObservation { (navigator?.hardwareConcurrency as? Number)?.toInt()?.takeIf { it > 0 } }
        ?: 1

private fun jsString(value: dynamic): String? = (value as? String)?.takeIf { it.isNotBlank() }

private inline fun <T> jsObservation(block: () -> T): T? = try {
    block()
} catch (_: dynamic) {
    null
}
