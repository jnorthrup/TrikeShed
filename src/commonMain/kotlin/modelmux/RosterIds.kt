package modelmux

/**
 * One endpoint's identity as the roster knows it: which provider serves it, and
 * what that provider calls the model.
 */
data class RosterEntry(val provider: String, val model: String)

/**
 * Assign a UNIQUE, routable card id to every roster endpoint.
 *
 * [ModelMux.session] resolves a model by scanning for the FIRST entry whose id
 * matches. That makes a duplicate model id not an ambiguity but a silent
 * deletion: the second provider offering the same model is registered, listed
 * by `mux.models`, shown in the panel — and unreachable, because every call for
 * that id lands on the first card.
 *
 * The roster collides in exactly this way today. `z-ai/glm-5.2` is served by
 * both nvidia and openrouter, and `nousresearch/hermes-3-llama-3.1-405b` by
 * three hermes endpoints. Since nvidia is registered first, the openrouter route
 * — the one with working credit on this machine — could not be selected at all.
 * The panel offered a menu whose entries mostly failed, and the reason was not
 * visible from anything the panel could show.
 *
 * The assignment:
 *  - the FIRST endpoint claiming a model id keeps the BARE id, so presets and
 *    panels that already name a model by its plain id keep working;
 *  - every LATER endpoint claiming that same id gets `"<provider>/<model>"`,
 *    which is unambiguous and reads the way openrouter already names models.
 *
 * The result has exactly one id per endpoint — nothing is duplicated and nothing
 * is shadowed. Order is preserved so the assignment is deterministic: the same
 * roster always produces the same ids, which matters because those ids end up in
 * receipts, cache keys and saved presets.
 */
fun disambiguateModelIds(entries: List<RosterEntry>): List<String> {
    val claimed = HashSet<String>(entries.size)
    val out = ArrayList<String>(entries.size)
    for (e in entries) {
        val id = if (claimed.add(e.model)) {
            e.model
        } else {
            // Qualify, and keep qualifying if even the qualified form repeats
            // (the same provider listing one model twice under different
            // endpoint names — hermes does this).
            var candidate = "${e.provider}/${e.model}"
            var n = 2
            while (!claimed.add(candidate)) {
                candidate = "${e.provider}/${e.model}#$n"
                n++
            }
            candidate
        }
        out.add(id)
    }
    return out
}

/**
 * The endpoints whose id had to be qualified because something already held the
 * bare name — i.e. the routes that were previously unreachable. Worth reporting
 * at wiring time: a shadowed provider is invisible at every other layer.
 */
fun shadowedEntries(entries: List<RosterEntry>): List<Pair<RosterEntry, String>> {
    val ids = disambiguateModelIds(entries)
    val out = ArrayList<Pair<RosterEntry, String>>()
    for (i in entries.indices) if (ids[i] != entries[i].model) out.add(entries[i] to ids[i])
    return out
}
