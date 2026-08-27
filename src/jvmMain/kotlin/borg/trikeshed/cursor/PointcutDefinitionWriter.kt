package borg.trikeshed.cursor

import borg.trikeshed.graal.ConfixBlackboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * H5 write side — applies pointcut definition documents from the blackboard funnel to the
 * live [TypedefProductionSystem]. The read side (adapter → blackboard keys, `GET
 * /blackboard/sites`) always existed; this is the seam that makes a written definition
 * change what executes.
 *
 * A definition is a document under `pointcut-def/<owner>/<method>/<site>` with an
 * `enabled` field. Applying it is NOT just landing the document:
 *   - enabled=false → [TypedefProductionSystem.suppressSite]: the site's events are dropped
 *     at the source, so no slab ever carries them.
 *   - enabled=true  → [TypedefProductionSystem.enableSite]: lifts a prior suppression.
 *
 * [BlackboardWire]'s `POST /blackboard/assert` handler routes these keys here, so a
 * definition posted through the single-writer funnel is observably applied — the
 * not-theater proof is that subsequent execution follows the written definition.
 */
class PointcutDefinitionWriter(
    private val blackboard: ConfixBlackboard,
    scope: CoroutineScope,
) {
    companion object {
        /** Definitions ride the assert funnel under this key namespace. */
        const val DEFINITION_PREFIX = "pointcut-def/"
    }

    data class Definition(val owner: String, val method: String, val site: Int, val enabled: Boolean)

    private val applied = Channel<Definition>(Channel.UNLIMITED)
    val appliedDefinitions = mutableListOf<Definition>()

    init {
        scope.launch {
            for (d in applied) appliedDefinitions += d
        }
    }

    /**
     * Parse one definition document body (the JSON a client POSTed to `/blackboard/assert`),
     * persist it under `pointcut-def/<owner>/<method>/<site>` via the single-writer funnel,
     * and apply it to the runtime. Returns the applied [Definition] (the blackboard key is
     * the source of truth; the runtime state follows).
     */
    fun writeDefinition(owner: String, method: String, site: Int, enabled: Boolean): Definition {
        val key = "$DEFINITION_PREFIX$owner/$method/$site"
        val doc = mapOf("method" to method, "site" to site.toString(), "enabled" to enabled.toString())
        blackboard.put(key, doc, "ide")
        val def = Definition(owner, method, site, enabled)
        // Apply BEFORE returning from the assert funnel: the next execution must observe the
        // written definition, not race an asynchronous consumer.
        if (enabled) TypedefProductionSystem.enableSite(method, site)
        else TypedefProductionSystem.suppressSite(method, site)
        applied.trySend(def)
        return def
    }

    /**
     * The assert-funnel entry point: [BlackboardWire] calls this for every POSTed key.
     * Keys under [DEFINITION_PREFIX] are parsed and applied; everything else is returned
     * `null` so the caller's plain `blackboard.put` path handles it. The value map's
     * `site`/`enabled` fields are read; the key's last two segments are the fallback.
     */
    fun applyFunnelDoc(key: String, value: Any?): Definition? {
        if (!key.startsWith(DEFINITION_PREFIX)) return null
        val rest = key.removePrefix(DEFINITION_PREFIX) // owner/method/site
        val parts = rest.split('/')
        if (parts.size < 3) return null
        val owner = parts[0]
        val method = parts[1]
        val site = parts[2].toIntOrNull() ?: return null
        val map = value as? Map<*, *>
        val enabled = (map?.get("enabled") as? String)?.toBoolean()
            ?: parts.getOrNull(3)?.toBoolean()
            ?: true
        return writeDefinition(owner, method, site, enabled)
    }
}
