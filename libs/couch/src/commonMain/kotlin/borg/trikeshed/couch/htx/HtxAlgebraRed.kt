package borg.trikeshed.couch.htx

/**
 * RED-phase stubs for HTX search & transformation algebra.
 *
 * Each function throws [NotImplementedError] — these exist only so that
 * HtxSearchRedTest compiles.  The test file documents the desired algebra;
 * implementation replaces stubs one by one.
 */
// ── Search algebra ──────────────────────────────────────────────

fun byBlockType(msg: HtxMessage, type: HtxBlockType): Sequence<HtxBlockData> =
    throw NotImplementedError("RED: byBlockType")

fun startLine(msg: HtxMessage): HtxStartLine? =
    throw NotImplementedError("RED: startLine")

fun headers(msg: HtxMessage): Sequence<Pair<ByteArray, ByteArray>> =
    throw NotImplementedError("RED: headers")

fun dataBlocks(msg: HtxMessage): Sequence<HtxBlockData.Data> =
    throw NotImplementedError("RED: dataBlocks")

fun trailers(msg: HtxMessage): Sequence<HtxBlockData.Trailer> =
    throw NotImplementedError("RED: trailers")

fun findHeader(msg: HtxMessage, name: ByteArray): ByteArray? =
    throw NotImplementedError("RED: findHeader")

fun blockCount(msg: HtxMessage): Int =
    throw NotImplementedError("RED: blockCount")

fun hasFlag(msg: HtxMessage, flag: HtxFlags): Boolean =
    throw NotImplementedError("RED: hasFlag")

fun isRequest(msg: HtxMessage): Boolean =
    throw NotImplementedError("RED: isRequest")

fun isResponse(msg: HtxMessage): Boolean =
    throw NotImplementedError("RED: isResponse")

// ── Serialization algebra ───────────────────────────────────────

fun HtxMessage.serialize(): ByteArray =
    throw NotImplementedError("RED: HtxMessage.serialize")

fun HtxMessage.Companion.deserialize(bytes: ByteArray): HtxMessage? =
    throw NotImplementedError("RED: HtxMessage.deserialize")

fun HtxMessage.toHttp1(): ByteArray =
    throw NotImplementedError("RED: HtxMessage.toHttp1")

fun parseHttp1(bytes: ByteArray): HtxMessage? =
    throw NotImplementedError("RED: parseHttp1 (free function)")

fun normalizeToHtx(bytes: ByteArray): HtxMessage =
    throw NotImplementedError("RED: normalizeToHtx (free function)")

// ── Transformation algebra ──────────────────────────────────────

fun HtxMessage.mergeTrailers(): HtxMessage =
    throw NotImplementedError("RED: HtxMessage.mergeTrailers")

fun HtxMessage.stripBody(): HtxMessage =
    throw NotImplementedError("RED: HtxMessage.stripBody")

fun HtxMessage.withFlag(flag: HtxFlags): HtxMessage =
    throw NotImplementedError("RED: HtxMessage.withFlag")

// ── Construction algebra (DSL factories) ────────────────────────

class HtxMessageBuilder {
    val headers = mutableListOf<Pair<ByteArray, ByteArray>>()
    fun header(name: ByteArray, value: ByteArray) { headers.add(name to value) }
}

fun request(method: HttpMethod, uri: ByteArray, block: HtxMessageBuilder.() -> Unit = {}): HtxMessage =
    throw NotImplementedError("RED: request factory")

fun response(status: Int, reason: ByteArray, block: HtxMessageBuilder.() -> Unit = {}): HtxMessage =
    throw NotImplementedError("RED: response factory")
