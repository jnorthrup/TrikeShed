package borg.trikeshed.uring

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey

class LiburingFacadeElement : AsyncContextElement() {
    override val key get() = Key

    companion object Key : AsyncContextKey<LiburingFacadeElement>()
}
