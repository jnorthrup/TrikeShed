package borg.trikeshed.reactor

import kotlinx.coroutines.flow.MutableSharedFlow
import borg.trikeshed.io.ByteBuffer
import borg.trikeshed.lib.CommonSelector
import borg.trikeshed.lib.SelectableChannel
import borg.trikeshed.lib.Join

internal expect class SelectorThread {
    val selector: CommonSelector
    val taskFlow: MutableSharedFlow<suspend () -> Unit>
    val writeFlow: MutableSharedFlow<Join<SelectableChannel, ByteBuffer>>
}
