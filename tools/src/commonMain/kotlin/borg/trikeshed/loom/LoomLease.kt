package borg.trikeshed.loom

import borg.trikeshed.htx.*
import kotlinx.coroutines.*

/**
 * cocaine-rats `crates/loom-mesh/src/bin/loom-lease.rs`: the lease keeper ([Keeper.cli]) over the platform's
 * HTX reactor. Runs in trusted custody only; prints non-secret receipts. Failures exit 2 behind `loom-lease: `.
 */
fun loomLease(args: Array<String>) = runBlocking {
    val reactor = openHtxReactorElement()
    try {
        Keeper.cli(args.toList(), reactor)
    } finally {
        reactor.close()
    }
}
