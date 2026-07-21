package borg.trikeshed.modelmux

sealed class SelectionRule {
    data object MinCost : SelectionRule()
    data object MinLatency : SelectionRule()
    data class SpecificProvider(val id: String) : SelectionRule()
    data class Fallback(val primary: String, val secondary: String) : SelectionRule()
}
