package borg.trikeshed.lib

import java.nio.channels.SelectionKey

typealias Interest = Int

typealias AsyncReaction = borg.trikeshed.lib.Join<Interest, UnaryAsyncReaction>

interface UnaryAsyncReaction : (SelectionKey) -> (AsyncReaction?){
    companion object {
        const val OP_READ = 1
        const val OP_WRITE = 4
        const val OP_CONNECT = 8
        const val OP_ACCEPT = 16
    }
}


