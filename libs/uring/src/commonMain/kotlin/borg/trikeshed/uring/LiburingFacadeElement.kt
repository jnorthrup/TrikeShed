package borg.trikeshed.uring

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState

class LiburingFacadeElement : AsyncContextElement() {
    override val key get() = Key

    companion object Key : AsyncContextKey<LiburingFacadeElement>()

    // Stub fields for future real ring state implementation
    var ringFd: Int = -1
        private set

    override suspend fun open() {
        if (state == ElementState.CREATED) {
            // Stub: call io_uring_setup, store ring fd, mmap SQ/CQ rings
            ringFd = 0 // placeholder
            super.open() // Transitions to OPEN
            // Then it should transition to ACTIVE once submit/completions starts
            state = ElementState.ACTIVE
        }
    }

    override suspend fun drain() {
        if (state.isAtLeast(ElementState.OPEN) && state.isLessThan(ElementState.DRAINING)) {
            state = ElementState.DRAINING
            // Stub: drain completion queue, reap all pending CQEs
            super.close() // Calls supervisor.cancel() and transitions to CLOSED
        }
    }

    override suspend fun close() {
        if (state.isAtLeast(ElementState.OPEN) && state.isLessThan(ElementState.CLOSED)) {
            if (state < ElementState.DRAINING) {
                state = ElementState.DRAINING
            }
            // Stub: call io_uring_exit, unmap rings
            ringFd = -1
            super.close()
        }
    }
}
