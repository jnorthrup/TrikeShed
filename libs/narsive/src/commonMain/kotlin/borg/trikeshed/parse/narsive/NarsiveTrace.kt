package borg.trikeshed.parse.narsive

data class NarsiveTrace(
    val label: String,
    val token: String? = null,
    val parse: Any? = null,
)
