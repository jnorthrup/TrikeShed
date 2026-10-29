package borg.trikeshed.jeva

import borg.trikeshed.narsese.JevaConfig
import borg.trikeshed.narsese.JevaElement
import borg.trikeshed.narsese.JevaRuntime
import borg.trikeshed.userspace.nio.platform.spi.SystemOperations
import kotlinx.coroutines.coroutineScope

suspend fun main() = coroutineScope {
    val model = SystemOperations.default.getenv("TRIKESHED_JEVA_MODEL")
        ?: "/Users/jim/.local/share/TrikeShed/jeva/releases/jeva-4b-mlx-q4g32-dist"
    val config = JevaConfig(model = model, device = "mlx", runtime = JevaRuntime.PYTHON)
    val element = JevaElement(this, config)
    println(element.metadata)
    element.drain()
}
