@file:OptIn(kotlin.ExperimentalUnsignedTypes::class)
package borg.trikeshed.torrent

interface TorrentHostAPI {
    suspend fun start()
    suspend fun stop()
    suspend fun addTorrent(magnetUri: CharSequence): CharSequence
    suspend fun removeTorrent(infoHash: ByteArray)
}

data class InfoHash(val bytes: ByteArray)
typealias PeerId = ByteArray
