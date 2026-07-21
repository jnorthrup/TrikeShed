package borg.trikeshed.context.nuid

sealed interface Nonce {
    data class RandomNonce(val value: String) : Nonce
    data class Derived(val parent: Nonce, val salt: String) : Nonce
}
