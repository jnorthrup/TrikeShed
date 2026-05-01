package borg.trikeshed.torrent.protocol

enum class MessageType(val id: Byte) {
    CHOKE(0), UNCHOKE(1), INTERESTED(2), NOT_INTERESTED(3),
    HAVE(4), BITFIELD(5), REQUEST(6), PIECE(7), CANCEL(8), PORT(9), EXTENSION(20)
}

sealed class PeerMessage {
    data class Handshake(val infoHash: ByteArray, val peerId: ByteArray): PeerMessage()
    object KeepAlive: PeerMessage()
    data class Request(val pieceIndex:Int, val offset:Int, val length:Int): PeerMessage()
    data class Piece(val pieceIndex:Int, val offset:Int, val data:ByteArray): PeerMessage()
}
