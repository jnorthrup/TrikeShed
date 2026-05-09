package borg.trikeshed.combined

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey

open class CombinedClientElement : AsyncContextElement() {
    companion object Key : AsyncContextKey<CombinedClientElement>()
    override val key: AsyncContextKey<CombinedClientElement> get() = Key
}
