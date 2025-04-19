package borg.trikeshed.reactor

import kotlinx.coroutines.flow.MutableSharedFlow
import borg.trikeshed.lib.Join

class SelectorThread(
    val selector: SelectorInterface,
    val taskFlow: MutableSharedFlow<suspend () -> Unit>,
    val writeFlow: MutableSharedFlow<Join<SelectableChannel, ByteBuffer>>
)
