package borg.trikeshed.vm

/**
 * Line protocol between a host and a sub-VM child (a JVM running `SubVmMain`, a GraalJS `node`
 * launcher, a native guest, …). One envelope per line, both directions, and the envelope IS a
 * [Teleported.Obj] in canonical form — one encoder, one exact parser, no second JSON library on the
 * wire (a generic parser collapses Num/Real and mangles escapes). Portable: the host half lives in
 * commonMain ([ProcessIsolateHost]); only the pipe is per-target.
 *
 *   host → child : {"id":n,"op":"eval","source":..,"name":..} | {"id":n,"op":"call","root":..,"args":[T..]}
 *                  | {"id":n,"op":"delegate","name":..} | {"id":n,"op":"interrupt"} | {"id":n,"op":"stats"} | {"id":n,"op":"close"}
 *                  | {"id":m,"value":T}  /  {"id":m,"error":".."}                          (reply to a host call)
 *   child → host : {"id":n,"ok":true,"value":T} | {"id":n,"ok":false,"kind":"GUEST_ERROR","error":".."}
 *                  | {"op":"host","id":m,"name":..,"args":[T..]}                          (guest → host delegation)
 */
object SubVmProtocol {
    fun encode(envelope: Teleported.Obj): String = envelope.canonical()
    fun decode(line: String): Teleported.Obj = Teleported.parseCanonical(line) as? Teleported.Obj
        ?: throw IllegalArgumentException("envelope must be an object: $line")

    /** Lossy adapter for foreign speakers that send generic JSON; a canonical STRING is parsed exactly. */
    fun teleportOf(v: Any?): Teleported = when (v) {
        null -> Teleported.Null
        is Teleported -> v
        is String -> runCatching { Teleported.parseCanonical(v) }.getOrElse { Teleported.Str(v) }
        is Boolean -> Teleported.Bool(v)
        is Number -> if (v is Double || v is Float) Teleported.Real(v.toDouble()) else Teleported.Num(v.toLong())
        is List<*> -> Teleported.Arr(v.map { teleportOf(it) })
        is Map<*, *> -> Teleported.Obj(v.entries.associate { it.key.toString() to teleportOf(it.value) })
        else -> Teleported.Opaque(v.toString())
    }

    /** The canonical string form — what [teleportOf] parses back exactly. */
    fun jsonOf(t: Teleported): String = t.canonical()
}
