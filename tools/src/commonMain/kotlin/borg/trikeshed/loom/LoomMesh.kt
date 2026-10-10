package borg.trikeshed.loom

/**
 * cocaine-rats `crates/loom-mesh/src/main.rs`. Failures exit 1 behind `loom-mesh: `. Ported:
 * `--confix-check` and `--settle-release`; each other mode is a TODO naming what it waits on.
 */
fun loomMesh(args: Array<String>) {
    when (args.firstOrNull()) {
        "--runpod-identity-diagnostic-stdin", "--runpod-identity-diagnostic" ->
            TODO("loom-mesh ${args[0]} waits on the Runpod API client (customer::provider_cost::runpod_api) over an HTTPS client")
        "--customer-legacy-preview", "--customer-legacy-import" ->
            TODO("loom-mesh ${args[0]} waits on customer::migration: the legacy customer book and the GCS client")
        "--confix-check" -> confixCheck(args.copyOfRange(1, args.size))
        "--uring-self-test" -> TODO("loom-mesh --uring-self-test waits on loom_mesh::uring::self_test over the userspace NIO uring facade")
        "--inference-worker" -> TODO("loom-mesh --inference-worker waits on inference::worker: the HTTP listener and HMAC-SHA256")
        "--inn" -> TODO("loom-mesh --inn waits on inn::serve: the HTTP listener, the sealed customer book (HKDF, ChaCha20-Poly1305) and the HTTPS client")
        "--settle", "--settle-public", "--settle-host" ->
            TODO("loom-mesh ${args[0]} waits on inn::serve_cloud_run: the HTTP listener and GCS settlement storage")
        "--inn-proxy" -> TODO("loom-mesh --inn-proxy waits on inn::proxy::serve: the HTTP listener and the HTTPS client")
        "--settle-held", "--settle-recovery-page", "--settle-init" ->
            TODO("loom-mesh ${args[0]} waits on CustomerStore over GCS settlement storage")
        "--settle-reconcile" -> TODO("loom-mesh --settle-reconcile waits on inn::reconcile_provider_file over GCS settlement storage")
        "--settle-pod-reconcile" -> TODO("loom-mesh --settle-pod-reconcile waits on the Runpod Pod billing client and GCS settlement storage")
        // Quiescence alone does not prove a zero-cost outcome; no hold is released without reconciliation.
        "--settle-release" -> error("provider-cost reconciliation required; automatic hold release is disabled")
        "--serverless" -> TODO("loom-mesh --serverless waits on the mesh node (Bootstrap, Node::open_serverless): Ed25519, the loom wire objects and the HTTP listener")
        "--config" -> TODO("loom-mesh --config waits on the mesh node (Config::load, Node::open): Ed25519, the loom wire objects, the HTTP listener and the peer HTTPS client")
        else -> error(
            "usage: loom-mesh --config PATH [-- PROGRAM ARGS...] | --serverless | --inference-worker PATH | --inn PATH | --inn-proxy PATH | --settle PATH | --settle-public PATH POLICY | --settle-host PATH POLICY | --settle-init PATH | --settle-held PATH | --settle-recovery-page PATH [AFTER] | --settle-reconcile PATH BUNDLE | --settle-pod-reconcile PATH POD_CONFIG RESERVATION | --runpod-identity-diagnostic RECONCILE_CONFIG | --settle-release PATH RESERVATION | --customer-legacy-preview MIGRATION | --customer-legacy-import MIGRATION GCS | --uring-self-test [auto|native|emulated]",
        )
    }
}

/** `--confix-check PATH...`: the preflight report of each configuration, `<path> <report>`. */
fun confixCheck(paths: Array<String>) {
    if (paths.isEmpty()) error("configuration paths required")
    for (path in paths) {
        val bytes = readFile(path, 128 * 1024 + 1, "configuration unavailable", "configuration unreadable")
        // zeroize::Zeroizing: the configuration bytes are cleared when they leave scope.
        try {
            println("$path ${assertConfix(bytes)}")
        } finally {
            bytes.fill(0)
        }
    }
}
