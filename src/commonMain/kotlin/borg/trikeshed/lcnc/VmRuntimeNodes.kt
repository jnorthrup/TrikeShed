package borg.trikeshed.lcnc

import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.pointcut.VmFacet
import borg.trikeshed.vm.Teleported
import borg.trikeshed.vm.VmBudget
import borg.trikeshed.vm.VmCapabilityReport
import borg.trikeshed.vm.VmHandle
import borg.trikeshed.vm.VmHost
import borg.trikeshed.vm.VmSpec
import borg.trikeshed.vm.VmSupervisor
import borg.trikeshed.vm.VmTrust
import borg.trikeshed.vm.vmFacetOf

/** The host already owns the registry used by VmWire; these facets borrow it. */
object VmHostKey : LcncServiceKey<VmHost>("VmHostKey")
object VmProviderReportsKey : LcncServiceKey<() -> List<VmCapabilityReport>>("VmProviderReportsKey")

object VmRuntimeNodes {
    const val SPAWN = "vm.spawn"
    const val EVAL = "vm.eval"
    const val REVOKE = "vm.revoke"
    const val CALL = "vm.call"
    const val STATS = "vm.stats"
    const val TIERS = "vm.tiers"
    const val PYTEST = "pytest.pure"

    fun runners(
        host: VmHost,
        reports: () -> List<VmCapabilityReport> = { VmSupervisor.reports },
    ): Map<String, LcncNodeRunner> = mapOf(
        SPAWN to spawn(host), EVAL to eval(host), REVOKE to revoke(host),
        CALL to call(host), STATS to stats(host), TIERS to tiers(reports), PYTEST to pytest(host),
    )

    fun spawn(host: VmHost) = boundLcnc(VmHostKey(host)) { service, node, inputs ->
        val id = id(string(node, inputs, "id", "vm-panel"))
        val facetName = string(node, inputs, "facet", "python")
        val facet = requireNotNull(vmFacetOf(facetName)) { "unknown VM facet '$facetName'" }
        val trust = trust(node, inputs)
        val world = strings(node, inputs, "world")
        require(world.isEmpty() || trust == VmTrust.OWN) { "VM world requires OWN trust" }
        val module = value(node, inputs, "module")?.let {
            require(it is String && it.isNotBlank()) { "module must be nonblank text" }
            it
        }
        require(module == null || trust == VmTrust.OWN) { "VM module requires OWN trust" }
        val spec = VmSpec(id, facet, trust, budget(node, inputs), world, module)
        check(service.value.get(id) == null) { "VM '$id' already exists" }
        val handle = service.value.spawn(spec)
        mapOf("vmId" to handle.id)
    }

    fun eval(host: VmHost) = boundLcnc(VmHostKey(host)) { service, node, inputs ->
        val handle = handle(service.value, node, inputs)
        val source = string(node, inputs, "source", allowBlank = true)
        val result = handle.eval(source, string(node, inputs, "name", node.id))
        mapOf("value" to hostValue(result), "cid" to result.cid.value)
    }

    fun revoke(host: VmHost) = boundLcnc(VmHostKey(host)) { service, node, inputs ->
        val id = id(string(node, inputs, "vmId"))
        checkNotNull(service.value.get(id)) { "no VM '$id'" }
        service.value.revoke(id, string(node, inputs, "reason", "lcnc"))
        mapOf("ok" to true)
    }

    fun call(host: VmHost) = boundLcnc(VmHostKey(host)) { service, node, inputs ->
        val handle = handle(service.value, node, inputs)
        val root = string(node, inputs, "root")
        val raw = value(node, inputs, "args")
        val args = when (raw) {
            null -> emptyList()
            is List<*> -> raw.map { Teleported.ofHost(it) }
            is Teleported.Arr -> raw.v
            is String -> (Teleported.parseCanonical(raw) as? Teleported.Arr)?.v
                ?: throw IllegalArgumentException("args must be a JSON array")
            else -> throw IllegalArgumentException("args must be an array")
        }
        require(args.none { it.isOpaque }) { "VM call arguments must be teleportable values" }
        mapOf("value" to hostValue(handle.call(root, *args.toTypedArray())))
    }

    fun stats(host: VmHost) = boundLcnc(VmHostKey(host)) { service, node, inputs ->
        val stats = handle(service.value, node, inputs).stats()
        mapOf("stats" to mapOf(
            "evals" to stats.evals, "calls" to stats.calls, "hostCalls" to stats.hostCalls,
            "refutations" to stats.refutations, "interrupted" to stats.interrupted,
        ))
    }

    fun tiers(reports: () -> List<VmCapabilityReport> = { VmSupervisor.reports }) =
        boundLcnc(VmProviderReportsKey(reports)) { service, _, _ ->
            mapOf("tiers" to service.value().map { it.toMap() })
        }

    fun pytest(host: VmHost) = boundLcnc(VmHostKey(host)) { service, node, inputs ->
        val handle = handle(service.value, node, inputs)
        require(handle.facet == VmFacet.GRAAL_PYTHON) { "pytest.pure requires a Python VM" }
        val path = string(node, inputs, "path", "/workspace/computronium/tests")
        val flags = string(node, inputs, "flags", "-q -s -o addopts= -p no:cacheprovider -p no:cov --continue-on-collection-errors", allowBlank = true)
        // JSON-quoted strings become Python data; shlex handles quoted pytest arguments.
        val source = """
            import io, sys, shlex, contextlib, json
            sys.path[:0] = ['/workspace/puresite', '/workspace/computronium']
            _lcnc_args = shlex.split(json.loads(${pythonString(JsonSupport.stringify(flags))}))
            _lcnc_args.append(json.loads(${pythonString(JsonSupport.stringify(path))}))
            import pytest
            _lcnc_buf = io.StringIO()
            with contextlib.redirect_stdout(_lcnc_buf), contextlib.redirect_stderr(_lcnc_buf):
                _lcnc_exit = int(pytest.main(_lcnc_args))
            (_lcnc_exit, _lcnc_buf.getvalue()[-2000:])
        """.trimIndent()
        val result = handle.eval(source, "lcnc-pytest") as? Teleported.Arr
            ?: error("pytest.pure did not return an exit code and output")
        require(result.v.size == 2) { "pytest.pure returned an invalid result" }
        val exit = result.v[0] as? Teleported.Num ?: error("pytest.pure exit code is not an integer")
        val tail = result.v[1] as? Teleported.Str ?: error("pytest.pure output is not text")
        require(exit.v in 0L..5L) { "pytest.pure returned an invalid exit code" }
        mapOf("exit" to exit.v, "tail" to tail.v)
    }

    private fun pythonString(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"

    private fun handle(host: VmHost, node: LcncNode, inputs: Map<String, Any?>): VmHandle {
        val id = id(string(node, inputs, "vmId"))
        val handle = checkNotNull(host.get(id)) { "no VM '$id'" }
        check(handle.isAlive) { "VM '$id' is not alive" }
        return handle
    }

    internal fun id(value: String): String {
        require(Regex("[A-Za-z0-9._:-]{1,128}").matches(value)) { "invalid VM id '$value'" }
        return value
    }

    internal fun value(node: LcncNode, inputs: Map<String, Any?>, key: String): Any? = when {
        inputs.containsKey(key) -> inputs[key]
        inputs.containsKey("$key?") -> inputs["$key?"]
        else -> node.params["in:$key"] ?: node.params[key]
    }

    internal fun string(node: LcncNode, inputs: Map<String, Any?>, key: String, default: String? = null, allowBlank: Boolean = false): String {
        val value = value(node, inputs, key) ?: default
        require(value is String && (allowBlank || value.isNotBlank())) { "$key must be ${if (allowBlank) "" else "nonblank "}text" }
        return value
    }

    internal fun strings(node: LcncNode, inputs: Map<String, Any?>, key: String): List<String> = when (val value = value(node, inputs, key)) {
        null -> emptyList()
        is String -> value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        is List<*> -> value.map { require(it is String && it.isNotBlank()) { "$key must contain text" }; it }
        else -> throw IllegalArgumentException("$key must be text or a list of text")
    }

    internal fun trust(node: LcncNode, inputs: Map<String, Any?>): VmTrust {
        val value = string(node, inputs, "trust", "OWN").uppercase()
        return requireNotNull(VmTrust.entries.firstOrNull { it.name == value }) { "unknown VM trust '$value'" }
    }

    internal fun budget(node: LcncNode, inputs: Map<String, Any?>): VmBudget {
        fun limit(key: String): Long {
            val value = value(node, inputs, key) ?: return 0L
            val number = when (value) {
                is Byte, is Short, is Int, is Long -> (value as Number).toLong()
                is String -> value.toLongOrNull()
                is Double, is Float -> (value as Number).toDouble().let {
                    if (it.isFinite() && it >= 0 && it < Long.MAX_VALUE.toDouble() && it % 1.0 == 0.0) it.toLong() else null
                }
                else -> null
            }
            require(number != null && number >= 0) { "$key must be a nonnegative integer" }
            return number
        }
        return VmBudget(limit("statements"), limit("wallMillis"), limit("calls"))
    }

    private fun hostValue(value: Teleported): Any? = when (value) {
        Teleported.Null -> null
        is Teleported.Bool -> value.v
        is Teleported.Num -> value.v
        is Teleported.Real -> value.v
        is Teleported.Str -> value.v
        is Teleported.Arr -> value.v.map { hostValue(it) }
        is Teleported.Obj -> value.v.mapValues { hostValue(it.value) }
        is Teleported.Bytes, is Teleported.Opaque -> JsonSupport.parse(value.canonical())
    }
}
