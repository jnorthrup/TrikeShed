package borg.trikeshed.lcnc

import kotlin.coroutines.CoroutineContext

enum class LcncContextRole { INVOCATION, SCOPE, BINDING, PRESENTATION, COMPOSITE, UNDECLARED }

/** Runtime identities stay typed; names below are only their published representation. */
data class LcncContextContract(
    val role: LcncContextRole,
    val key: CoroutineContext.Key<*>?,
    val keyName: String?,
    val element: String?,
    val exception: String? = null,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "role" to role.name.lowercase(), "key" to keyName,
        "element" to element, "exception" to exception,
    )

    companion object {
        fun of(type: String, composite: Boolean = false): LcncContextContract {
            if (composite) return LcncContextContract(LcncContextRole.COMPOSITE,
                LcncScopeFrame.Key, "LcncScopeFrame.Key", "LcncScopeFrame",
                "A stored program constructs a scope frame; its statements introduce their own invocation keys.")
            return when (type) {
                LcncContracts.SCOPE -> LcncContextContract(LcncContextRole.SCOPE,
                    LcncScopeFrame.Key, "LcncScopeFrame.Key", "LcncScopeFrame")
                LcncContracts.SCOPE_IN, LcncContracts.SCOPE_OUT -> LcncContextContract(LcncContextRole.BINDING,
                    LcncScopeFrame.Key, "LcncScopeFrame.Key", "LcncScopeFrame",
                    "Reads or yields the enclosing frame; does not construct a separate element.")
                "note", "program.ref" -> LcncContextContract(LcncContextRole.PRESENTATION,
                    null, null, null, "Presentation only; does not execute. A loaded program body constructs a scope frame.")
                else -> LcncNodeKey.of(type)?.let { key ->
                    LcncContextContract(LcncContextRole.INVOCATION, key, "LcncNodeKey.${key.name}", "LcncNodeElement")
                } ?: LcncContextContract(LcncContextRole.UNDECLARED, null, null, null,
                    "No invocation key or structural exception is declared.")
            }
        }
    }
}
