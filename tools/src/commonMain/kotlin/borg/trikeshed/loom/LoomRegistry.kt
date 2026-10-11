package borg.trikeshed.loom

import borg.trikeshed.htx.*
import borg.trikeshed.lib.*
import kotlinx.coroutines.*

/**
 * cocaine-rats `crates/loom-mesh/src/bin/loom-registry.rs`. Failures exit 2 behind `loom-registry: `.
 *   loom-registry verify <oci-layout-dir>   check a layout before upload
 *   loom-registry serve <config.json>       serve default tags
 * [supervisor] is the platform's seam for the listener.
 */
fun loomRegistry(args: Array<String>, supervisor: Supervisor) {
    when {
        args.size == 2 && args[0] == "verify" -> for ((digest, name) in verifyLayout(args[1])) println("$digest $name")
        args.size == 2 && args[0] == "serve" -> serve(args[1], supervisor)
        else -> error("usage: loom-registry verify <oci-layout-dir> | serve <config.json>")
    }
}

/**
 * loom-registry.rs serve: the configuration at [file], every default manifest fetched and verified over the platform's
 * HTX reactor, a `serving` line per default, then the OCI routes on `listen` until the process is stopped.
 */
fun serve(file: String, supervisor: Supervisor): Unit = runBlocking(Dispatchers.Default) {
    // read_to_string: a file that is not UTF-8 is unreadable.
    val text = readFile(file, Int.MAX_VALUE, "config read", "config read")
    if (!JsonReader.utf8(text, text.size)) error("config read")
    val config = try {
        RegistryConfig.from_slice(text)
    } catch (refused: IllegalStateException) {
        error("config invalid")
    }
    val reactor = openHtxReactorElement()
    val registry = try {
        Registry.load(config, reactor)
    } finally {
        reactor.close()
    }
    for ((source, default) in registry.defaults().view) println("serving ${source.a}:${default.a} ${default.b} from ${source.b}")
    registry.open()
    val serve = try {
        supervisor.bind(registry.listener, config.listen)
    } catch (refused: IllegalStateException) {
        error("listen")
    }
    try {
        serve()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
    }
    error("serve")
}
