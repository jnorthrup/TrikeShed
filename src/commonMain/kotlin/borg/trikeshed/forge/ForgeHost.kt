@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.forge

/**
 * Host-view decisions — script.js "Render: host" minus the DOM: the capability tiles derived from
 * `seed.hosts` (+ `seed.dashboards.nio` fallback) and the live-probe phrasing. The tiles, spawn
 * form, and EventSource wiring are the jsForge adapter.
 */

data class ForgeHostTile(
    val key: String,
    val value: String,
    val sub: String,
    val tone: String?,
    /** `dead features` tiles carry the discontinued list verbatim. */
    val deadList: List<String> = emptyList(),
)

/**
 * `renderHostTiles`' derivation. `live` is the probe verdict: null = probing, true = live server,
 * false = static dump. A missing seed degrades to the same dead host the script assumed.
 */
fun forgeHostTiles(hosts: Map<String, Any?>, dashboardsNio: Map<String, Any?>?, live: Boolean?): List<ForgeHostTile> {
    val host = hosts["host"].asMap()
    val nio = host["nio"].asMap().takeIf { it.isNotEmpty() } ?: dashboardsNio ?: emptyMap()
    val tiles = mutableListOf<ForgeHostTile>()
    tiles += ForgeHostTile(
        key = "platform",
        value = host["platform"].asStr("none"),
        sub = when (live) {
            true -> "live server"
            false -> "static dump (baked on " + host["platform"].asStr("?") + ")"
            null -> "probing…"
        },
        tone = null,
    )
    val subVm = host["subVm"].asBool()
    tiles += ForgeHostTile(
        key = "sub-vm host",
        value = if (subVm) "bound" else "dead",
        sub = if (subVm) host["languages"].asList().joinToString(", ") { it.asStr() } +
            " · " + host["vms"].asInt() + " vms"
        else "vm.spawn is discontinued on this target",
        tone = if (subVm) "live" else "dead",
    )
    tiles += ForgeHostTile(
        key = "nio backend",
        value = nio["backendName"].asStr("unknown"),
        sub = (if (nio["ioUringAvailable"].asBool()) "io_uring · " else "") +
            nio["capabilities"].asList().joinToString(" ") { it.asStr() },
        tone = null,
    )
    for (p in hosts["providers"].asMaps()) {
        val available = p["available"].asBool()
        tiles += ForgeHostTile(
            key = "tier · " + p["providerId"].asStr(),
            value = if (available) p["sandboxKind"].asStr() else "unavailable",
            sub = p["languages"].asList().joinToString(",") { it.asStr() } +
                (if (p["wallBudgetSupported"].asBool()) " · wall budget" else " · no wall budget") +
                (if (p["callSupported"].asBool()) " · call" else " · no call") +
                (p["note"].asStrOrNull()?.let { " — $it" } ?: ""),
            tone = if (available) "live" else "dead",
        )
    }
    val dead = host["discontinued"].asList().map { it.asStr() }
    tiles += ForgeHostTile(
        key = "dead features",
        value = dead.size.toString(),
        sub = "",
        tone = if (dead.isEmpty()) "live" else "dead",
        deadList = dead,
    )
    return tiles
}

/** The default dead host the script fell back to when the seed carried none. */
val FORGE_DEAD_HOST_SEED: Map<String, Any?> = mapOf(
    "host" to mapOf(
        "platform" to "none",
        "subVm" to false,
        "languages" to emptyList<String>(),
        "vms" to 0,
        "nio" to emptyMap<String, Any?>(),
        "discontinued" to emptyList<String>(),
    ),
    "providers" to emptyList<Any?>(),
    "vms" to null,
)

fun forgeHostsOf(seed: Map<String, Any?>): Map<String, Any?> =
    if (seed["hosts"].asMap()["host"] != null) seed["hosts"].asMap() else FORGE_DEAD_HOST_SEED

/** The spawn form's facet options: the host's languages, or ["js"] when the host is dead. */
fun forgeHostFacets(hosts: Map<String, Any?>): List<String> =
    hosts["host"].asMap()["languages"].asList().map { it.asStr() }.ifEmpty { listOf("js") }
