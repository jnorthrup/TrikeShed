package borg.trikeshed.couch.transport.htx

import borg.trikeshed.couch.runtime.Reactor

class HtxBackedCouchTransport(reactor: Reactor) {
    fun view(database: String, path: String): HtxCouchExchange {
        val request = HtxRequest(
            method = "GET",
            path = "/$database/$path",
            accept = "application/json",
        )
        return HtxCouchExchange(request)
    }
}
