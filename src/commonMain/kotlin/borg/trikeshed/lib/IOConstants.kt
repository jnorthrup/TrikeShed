package borg.trikeshed.lib

object IOConstants {
    const val OP_READ = 1
    const val OP_WRITE = 4
    const val OP_CONNECT = 8
    const val OP_ACCEPT = 16
    
    const val DEFAULT_BUFFER_SIZE = 8192
    const val DEFAULT_BACKLOG = 50
    
    const val INFINITE_TIMEOUT = -1L
    const val NO_TIMEOUT = 0L
}
