package borg.trikeshed.torrent

import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.litebike.RawRoute

/** JVM HTTP response adaptation only; torrent dispatch and I/O live in commonMain. */
fun TorrentHttpRouter.rawRoute(): RawRoute = { method, path, payload, _ ->
    route(method, path, payload)?.let { reply ->
        JvmKanbanServer.HttpResponse(reply.status, "", reply.contentType, reply.bytes)
    }
}
