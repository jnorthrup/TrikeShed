package borg.trikeshed.context.nuid

sealed interface Capability {
    data object Process : Capability
    data object Cas : Capability
    data object Wireproto : Capability
    data object Mesh : Capability
    data object ModelMux : Capability
}
