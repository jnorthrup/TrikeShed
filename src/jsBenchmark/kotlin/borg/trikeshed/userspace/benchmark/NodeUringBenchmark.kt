package borg.trikeshed.userspace.benchmark

import borg.trikeshed.lib.processObj

/** Node owns argv; the workload and ring accounting stay in commonMain. */
suspend fun main() {
    val args = processObj.argv.slice(2) as Array<String>
    uringBenchmarkMain(args)
}
