package borg.trikeshed.loom

import borg.trikeshed.lib.*

/**
 * cocaine-rats crates/loom-mesh/src/config.rs Config: the trust fields the signed objects read. The
 * node's own fields (listen, store, key_file, limits, timers) and its JSON preflight come with the node.
 */
class Config(
    val id: String,
    val members: Series<Member>,
    val settlement_authority: String? = null,
    val gcs: GcsConfig? = null,
) {
    fun member(id: String): Member = members.view.firstOrNull { it.id == id } ?: error("unknown identity")
}
