package borg.trikeshed.loom

/**
 * cocaine-rats `crates/loom-mesh/src/bin/loom-registry.rs`. Failures exit 2 behind `loom-registry: `.
 *   loom-registry verify <oci-layout-dir>   check a layout before upload
 *   loom-registry serve <config.json>       serve default tags
 */
fun loomRegistry(args: Array<String>) {
    when {
        args.size == 2 && args[0] == "verify" -> for ((digest, name) in verifyLayout(args[1])) println("$digest $name")
        args.size == 2 && args[0] == "serve" ->
            TODO("loom-registry serve waits on the HTTPS client that fetches and verifies each default manifest (Registry::load) and the HTTP listener for the OCI routes")
        else -> error("usage: loom-registry verify <oci-layout-dir> | serve <config.json>")
    }
}
