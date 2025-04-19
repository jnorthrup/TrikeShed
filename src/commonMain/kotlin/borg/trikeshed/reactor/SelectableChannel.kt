package borg.trikeshed.reactor

expect interface SelectableChannel {
      fun configureBlocking(block: Boolean): SelectableChannel
      fun register(selector: SelectorInterface, ops: Int, attachment: Any?): SelectionKey
      fun close()
}
