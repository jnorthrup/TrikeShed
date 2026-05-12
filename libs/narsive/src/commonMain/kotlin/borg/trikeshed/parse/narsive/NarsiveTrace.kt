package borg.trikeshed.parse.narsive

data class NarsiveTrace(
    val label: CharSequence,
    val token: CharSequence? = null,
    val parse: Any? = null,
)
