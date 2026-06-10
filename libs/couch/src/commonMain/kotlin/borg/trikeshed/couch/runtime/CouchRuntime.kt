package borg.trikeshed.couch.runtime

import borg.trikeshed.couch.transport.htx.HtxBackedCouchTransport

class CouchRuntime(reactor: Reactor) {
    val reactor: Reactor = reactor
    val transport: HtxBackedCouchTransport = HtxBackedCouchTransport(reactor)
}
