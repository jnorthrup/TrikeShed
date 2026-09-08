package borg.trikeshed.userspace

import borg.trikeshed.lib.processObj
import borg.trikeshed.platform.HostDescriptor
import borg.trikeshed.platform.HostRuntime
import borg.trikeshed.platform.loadPlatformHost
import borg.trikeshed.platform.normalizeHostArchitecture
import borg.trikeshed.platform.normalizeHostOs
import kotlin.js.jsTypeOf

/** Resolve the configured Node module through Node's loader, then probe its shared uring backend. */
fun discoverNodeUringBackend(entries: Int): UringBackendDiscovery = discoverNodeUringBackend(
    entries, loadPlatformHost().descriptor, processObj,
    { nodeUringRequire(processObj) }, ::nodeNativeChannelBackend,
)

internal fun discoverNodeUringBackend(
    entries: Int,
    host: HostDescriptor,
    process: dynamic,
    requireModule: () -> dynamic,
    backendFactory: (dynamic, dynamic) -> UserspaceChannelBackend = ::nodeNativeChannelBackend,
): UringBackendDiscovery {
    require(entries > 0)
    fun unavailable(state: UringProbeState, detail: String, module: String? = null,
                    phase: UringProbePhase = UringProbePhase.DISCOVERY, errno: Int? = null) =
        UringBackendDiscovery(UringProbeReport(host, state, phase, module, detail, errno))
    if (host.runtime != HostRuntime.NODE_JS)
        return unavailable(UringProbeState.RUNTIME_RESTRICTED, "No Node runtime with native module access was identified")
    uringHostConstraint(host)?.let {
        return unavailable(it, "Node io_uring requires a known Linux arm64 or x86_64 host")
    }
    var moduleName: String? = null
    var phase = UringProbePhase.DISCOVERY
    var resolved = false
    try {
        moduleName = (process?.env?.TRIKESHED_URING_MODULE as? String)?.takeIf { it.isNotBlank() }
            ?: "@trikeshed/uring"
        val loader: dynamic = requireModule()
        if (jsTypeOf(loader) != "function" || jsTypeOf(loader.resolve) != "function")
            return unavailable(UringProbeState.RUNTIME_RESTRICTED, "Node module resolution/loading is unavailable", moduleName)
        val path = loader.resolve(moduleName) as? String
        if (path == null || path.isBlank())
            return unavailable(UringProbeState.MODULE_LOAD_FAILED, "Node module resolution returned no path", moduleName)
        resolved = true
        moduleName = path
        phase = UringProbePhase.LOAD
        val module: dynamic = loader(path)
        val exports = arrayOf("abiVersion", "platform", "architecture", "napiVersion", "open", "supports", "execute", "close")
        if (module == null || exports.any { jsTypeOf(module[it]) != "function" })
            return unavailable(UringProbeState.ABI_MISMATCH, "Node uring module does not export the ABI 1 functions", moduleName, phase)
        if (nodeUringVersion(module.abiVersion()) != 1)
            return unavailable(UringProbeState.ABI_MISMATCH, "Node uring bridge protocol must be version 1", moduleName, phase)
        if (normalizeHostOs(module.platform() as? String) != "linux")
            return unavailable(UringProbeState.ABI_MISMATCH, "Node uring module platform must be Linux", moduleName, phase)
        val architecture = normalizeHostArchitecture(module.architecture() as? String)
        if (architecture == null)
            return unavailable(UringProbeState.ABI_MISMATCH, "Node uring module architecture is unspecified", moduleName, phase)
        if (architecture != host.architecture)
            return unavailable(UringProbeState.ARCH_MISMATCH, "Module architecture $architecture does not match ${host.architecture}", moduleName, phase)
        val napi = nodeUringVersion(module.napiVersion())
        if (napi == null || napi < 1)
            return unavailable(UringProbeState.ABI_MISMATCH, "Node uring module N-API requirement is malformed", moduleName, phase)
        if (host.napiVersion == null)
            return unavailable(UringProbeState.HOST_UNKNOWN, "Node runtime N-API version is unavailable", moduleName, phase)
        if (napi > host.napiVersion)
            return unavailable(UringProbeState.ABI_MISMATCH, "Module requires N-API $napi; runtime supports ${host.napiVersion}", moduleName, phase)
        phase = UringProbePhase.SETUP
        val handle: dynamic = module.open(entries)
        if (jsTypeOf(handle) != "bigint")
            return unavailable(UringProbeState.PROBE_FAILED, "Node uring setup must return a BigInt handle or negative errno", moduleName, phase)
        if (js("handle <= BigInt(0)") as Boolean) {
            val errno = nodeUringErrno(handle)
            return unavailable(errno?.let(::uringErrnoState) ?: UringProbeState.PROBE_FAILED,
                "Node uring setup returned ${handle.toString()}", moduleName, phase, errno)
        }
        phase = UringProbePhase.OPERATIONS
        val backend = try {
            backendFactory(module, handle)
        } catch (failure: dynamic) {
            val cleanup = try {
                module.close(handle)
                ""
            } catch (closeFailure: dynamic) {
                "; module close failed: ${nodeUringFailure(closeFailure)}"
            }
            return unavailable(UringProbeState.PROBE_FAILED,
                "Native adapter construction failed: ${nodeUringFailure(failure)}$cleanup", moduleName, phase)
        }
        return probeUringBackend(host, path, backend)
    } catch (failure: dynamic) {
        val code = failure?.code as? String
        val detail = nodeUringFailure(failure)
        val state = when {
            code in arrayOf("ERR_ACCESS_DENIED", "ERR_PERMISSION_DENIED", "EACCES", "EPERM") -> UringProbeState.RUNTIME_RESTRICTED
            !resolved && code == "MODULE_NOT_FOUND" -> UringProbeState.MODULE_ABSENT
            code == "ERR_DLOPEN_FAILED" && arrayOf("NODE_MODULE_VERSION", "wrong ELF class", "invalid ELF header",
                "incompatible architecture", "Exec format error").any { it in detail } -> UringProbeState.ABI_MISMATCH
            phase == UringProbePhase.DISCOVERY || phase == UringProbePhase.LOAD -> UringProbeState.MODULE_LOAD_FAILED
            else -> UringProbeState.PROBE_FAILED
        }
        return unavailable(state, detail, moduleName, phase)
    }
}

/** createRequire works in ESM; module.require supplies the builtin on older CommonJS runtimes. */
internal fun nodeUringRequire(process: dynamic): dynamic {
    var moduleApi: dynamic = if (jsTypeOf(process?.getBuiltinModule) == "function") {
        process.getBuiltinModule("module")
    } else null
    if (moduleApi == null)
        moduleApi = js("typeof module !== 'undefined' && typeof module.require === 'function' ? module.require('module') : null")
    if (jsTypeOf(moduleApi?.createRequire) != "function" || jsTypeOf(process?.cwd) != "function") return null
    return moduleApi.createRequire((process.cwd() as String).trimEnd('/') + "/.trikeshed-uring.cjs")
}

private fun nodeUringVersion(value: dynamic): Int? = (value as? Number)?.toDouble()?.let {
    if (it.isFinite() && it >= 0 && it <= Int.MAX_VALUE && it == it.toInt().toDouble()) it.toInt() else null
}

internal fun nodeUringErrno(value: dynamic): Int? =
    js("typeof value === 'bigint' && value >= BigInt(-4095) && value <= BigInt(-1) ? Number(-value) : null") as? Int

private fun nodeUringFailure(value: dynamic): String =
    (value?.message as? String) ?: (js("String(value)") as String)
