package borg.trikeshed.context.nuid

data class Workgroup(
    val name: String,
    val scope: Subnet,
    val traits: TraitSpace
)
