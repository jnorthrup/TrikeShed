package borg.trikeshed.couch.runtime

import borg.trikeshed.couch.transport.htx.HtxBackedCouchTransport

class CouchRuntime {
    val transport: HtxBackedCouchTransport = HtxBackedCouchTransport()
}
