package borg.trikeshed.graal.subvm

import borg.trikeshed.vm.Teleported
import borg.trikeshed.vm.field
import borg.trikeshed.vm.str

/** One stock GraalPy context in its own process, with an explicit application environment. */
data class PythonWorker(
    val workingDirectory: String? = null,
    val pythonPath: List<String> = emptyList(),
    val executable: String? = null,
    val pythonHome: String? = null,
    val environment: Map<String, String> = emptyMap(),
    val arguments: List<String> = listOf("python"),
    val timeoutMillis: Long = 300_000,
) {
    init {
        require(timeoutMillis > 0)
    }

    internal fun encode(): String = SubVmProtocol.encode(Teleported.obj(
        "directory" to workingDirectory, "path" to pythonPath, "executable" to executable,
        "home" to pythonHome, "environment" to environment, "arguments" to arguments,
        "timeout" to timeoutMillis,
    ))

    companion object {
        internal fun decode(source: String): PythonWorker {
            val message = SubVmProtocol.decode(source)
            fun strings(name: String) = (message.field(name) as? Teleported.Arr)?.v.orEmpty().map {
                (it as? Teleported.Str)?.v ?: error("Python worker $name requires strings")
            }
            val environment = (message.field("environment") as? Teleported.Obj)?.v.orEmpty().mapValues {
                (it.value as? Teleported.Str)?.v ?: error("Python worker environment requires strings")
            }
            return PythonWorker(message.str("directory"), strings("path"), message.str("executable"),
                message.str("home"), environment, strings("arguments"),
                (message.field("timeout") as? Teleported.Num)?.v ?: 300_000)
        }
    }
}
