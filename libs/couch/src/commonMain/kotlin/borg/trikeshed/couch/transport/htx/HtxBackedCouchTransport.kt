package borg.trikeshed.couch.transport.htx

class HtxBackedCouchTransport {
    fun view(database: String, path: String): HtxCouchExchange {
        val request = HtxRequest(
            method = "GET",
            path = "/$database/$path",
            accept = "application/json",
        )
        return HtxCouchExchange(request)
    }
}
