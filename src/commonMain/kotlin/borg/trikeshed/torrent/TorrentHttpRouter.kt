package borg.trikeshed.torrent

import borg.trikeshed.couch.WireReply
import borg.trikeshed.parse.json.JsonSupport
import kotlinx.coroutines.CancellationException

/** HTTP request composition over the real torrent element; sockets remain owned by the caller. */
class TorrentHttpRouter(val element: TorrentElement) {
    /**
     * GET /torrent/status; POST /torrent/add (raw metainfo or JSON {uri,destinationDir});
     * POST /torrent/seed (bencoded {content: bytes, metadata: metainfo bytes});
     * GET /torrent/{hash}/download; POST /torrent/{hash}/download (UTF-8 output path);
     * DELETE or POST /torrent/{hash}/remove.
     */
    suspend fun route(method: String, path: String, body: ByteArray = byteArrayOf()): WireReply? {
        val target = path.substringBefore('?')
        if (target != "/torrent" && !target.startsWith("/torrent/")) return null
        if (body.size > TorrentBencode.MAX_BYTES) return WireReply.json(413, mapOf("error" to "torrent_request_size_limit"))
        return try {
            when (target) {
                "/torrent", "/torrent/status" -> {
                    if (method != "GET") return WireReply.methodNotAllowed(method)
                    val stats = element.stats.value
                    WireReply.json(200, mapOf("state" to element.state.name, "activeTorrents" to stats.activeTorrents,
                        "totalPeers" to stats.totalPeers, "downloadBytesPerSecond" to stats.totalDownloadSpeedBps,
                        "uploadBytesPerSecond" to stats.totalUploadSpeedBps,
                        "torrents" to element.activeTorrents.values.map(::describe)))
                }
                "/torrent/add" -> {
                    if (method != "POST") return WireReply.methodNotAllowed(method)
                    val handle = if (body.firstOrNull() == '{'.code.toByte()) {
                        val value = JsonSupport.parseStrict(BencodeValue.Bytes(body).text()) as? Map<*, *>
                            ?: throw IllegalArgumentException("Expected torrent request object")
                        val uri = value["uri"] as? String ?: throw IllegalArgumentException("Torrent uri required")
                        val destination = value["destinationDir"]
                        require(destination == null || destination is String) { "Invalid destinationDir" }
                        element.addTorrent(uri, destination as? String)
                    } else element.addTorrentData(body)
                    WireReply.json(201, describe(handle))
                }
                "/torrent/seed" -> {
                    if (method != "POST") return WireReply.methodNotAllowed(method)
                    val value = TorrentBencode.decode(body).dictionary()
                    val handle = element.seedTorrent(value["metadata"].bytes(), value["content"].bytes())
                    WireReply.json(201, describe(handle))
                }
                else -> {
                    val parts = target.split('/')
                    if (parts.size != 4) return WireReply.notFound("torrent_route")
                    val hash = parts[2]
                    TorrentElement.infoHash(hash)
                    if (element.getTorrent(hash) == null) return WireReply.notFound("torrent")
                    when (parts[3]) {
                        "remove" -> {
                            if (method != "DELETE" && method != "POST") return WireReply.methodNotAllowed(method)
                            element.removeTorrent(hash)
                            WireReply.json(200, mapOf("removed" to hash))
                        }
                        "download" -> when (method) {
                            "GET" -> WireReply(200, "application/octet-stream", element.download(hash))
                            "POST" -> {
                                val output = BencodeValue.Bytes(body).text()
                                require(output.isNotEmpty() && '\u0000' !in output) { "Output path required" }
                                element.downloadTo(hash, output)
                                WireReply.json(200, mapOf("infoHash" to hash, "path" to output))
                            }
                            else -> WireReply.methodNotAllowed(method)
                        }
                        else -> WireReply.notFound("torrent_route")
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: IllegalArgumentException) { WireReply.badRequest(failure.message ?: "Invalid torrent request") }
        catch (failure: IllegalStateException) { WireReply.json(409, mapOf("error" to "torrent_state", "reason" to failure.message)) }
        catch (failure: Exception) { WireReply.json(502, mapOf("error" to "torrent_operation", "reason" to failure.message)) }
    }

    private fun describe(handle: TorrentHandle): Map<String, Any?> = buildMap {
        put("infoHash", handle.infoHash.hex()); put("version", if (handle.infoHash.isV2) 2 else 1)
        put("name", handle.name); put("totalSize", handle.totalSize)
        put("pieceSize", handle.pieceSize); put("pieces", handle.numPieces)
        if (handle is TorrentSession) {
            val stats = handle.stats.value
            put("state", stats.state.name)
            put("peersConnected", stats.peersConnected); put("peersTotal", stats.peersTotal)
            put("seedsConnected", stats.seedsConnected); put("progressPermille", stats.progressPermille)
            put("downloadedBytes", stats.downloadedBytes); put("uploadedBytes", stats.uploadedBytes)
            put("downloadBytesPerSecond", stats.downloadSpeedBps); put("uploadBytesPerSecond", stats.uploadSpeedBps)
            put("lastFailure", handle.lastFailure.value)
        }
    }
}
