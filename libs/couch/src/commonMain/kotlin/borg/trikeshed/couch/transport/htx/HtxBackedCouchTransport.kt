package borg.trikeshed.couch.transport.htx

class HtxBackedCouchTransport {
    fun view(database: CharSequence, path: CharSequence): HtxCouchExchange {
        val request = HtxRequest(
            method = "GET",
            path = "/$database/$path",
            accept = "application/json",
        )
        return HtxCouchExchange(request)
    }
}
