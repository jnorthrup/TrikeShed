package borg.trikeshed.platform

import kotlinx.serialization.Serializable

/** Runtime identity is independent of the operating system hosting it. */
@Serializable
enum class HostRuntime { JVM, NODE_JS, BROWSER_JS, HOSTED_JS, JS, UNKNOWN }

/** Architecture describes the runtime ABI; translation may hide the physical CPU. */
@Serializable
data class HostDescriptor(
    val runtime: HostRuntime = HostRuntime.UNKNOWN,
    val rawOs: String? = null,
    val rawArchitecture: String? = null,
    val osVersion: String? = null,
    val cpuModel: String? = null,
    val cpuVendor: String? = null,
    val runtimeVersion: String? = null,
    val runtimeName: String? = null,
    val runtimeVendor: String? = null,
    val vmVersion: String? = null,
    val nativeAbi: String? = null,
    val jvmSpecificationVersion: String? = null,
    val napiVersion: Int? = null,
    val libc: String? = null,
) {
    val os: String? get() = normalizeHostOs(rawOs)
    val architecture: String? get() = normalizeHostArchitecture(rawArchitecture)
}

fun normalizeHostOs(value: String?): String? = when (val os = value?.trim()?.lowercase()) {
    null, "" -> null
    "darwin", "mac os x", "macos", "mac" -> "macos"
    "linux" -> "linux"
    "win32", "windows" -> "windows"
    else -> if (os.startsWith("windows ")) "windows" else os
}

fun normalizeHostArchitecture(value: String?): String? = when (val arch = value?.trim()?.lowercase()) {
    null, "" -> null
    "aarch64", "arm64" -> "arm64"
    "amd64", "x64", "x86_64" -> "x86_64"
    "ia32", "i386", "i486", "i586", "i686", "x86" -> "x86"
    else -> arch
}
