package borg.trikeshed.couch.htx

import java.util.LinkedList
/**
 * RED-phase stubs for HTX search & transformation algebra.
 *
 * Each function throws [NotImplementedError] — these exist only so that
 * HtxSearchRedTest compiles.  The test file documents the desired algebra;
 * implementation replaces stubs one by one.
 */
// ── Search algebra ──────────────────────────────────────────────

fun byBlockType(msg: HtxMessage, type: HtxBlockType): Sequence<HtxBlockData> = sequence {
    for (b in msg.blocks) {
        if (b.blockType == type) yield(b)
    }
}

fun startLine(msg: HtxMessage): HtxStartLine? = msg.startLine()

fun headers(msg: HtxMessage): Sequence<Pair<ByteArray, ByteArray>> = msg.headers()

fun dataBlocks(msg: HtxMessage): Sequence<HtxBlockData.Data> = msg.blocks.filterIsInstance<HtxBlockData.Data>().asSequence()

fun trailers(msg: HtxMessage): Sequence<HtxBlockData.Trailer> = msg.blocks.filterIsInstance<HtxBlockData.Trailer>().asSequence()

fun findHeader(msg: HtxMessage, name: ByteArray): ByteArray? =
    msg.headers().firstOrNull { it.first.decodeToString().equals(name.decodeToString(), ignoreCase = true) }?.second

fun blockCount(msg: HtxMessage): Int = msg.blocks.size

fun hasFlag(msg: HtxMessage, flag: HtxFlags): Boolean = (msg.flags and flag.mask) != 0u

fun isRequest(msg: HtxMessage): Boolean = msg.startLine()?.isRequest ?: false

fun isResponse(msg: HtxMessage): Boolean = msg.startLine()?.isRequest == false && msg.startLine() != null

// ── Serialization algebra ───────────────────────────────────────

/** HTX frame magic: "HTX\0" */const val HTX_MAGIC: Int = 0x48545800.toInt()

/** Serialize an HtxMessage to a framed binary format with CRC32 checksum. */
fun HtxMessage.serialize(): ByteArray {
    // Phase 1: encode blocks into a byte array (payload without magic/crc)
    val payload = buildPayload()
    // Phase 2: compute CRC32 over payload
    val crc = HtxCrc32.compute(payload)
    // Phase 3: assemble frame: magic(4) + payload + crc(4)
    val frame = ByteArray(4 + payload.size + 4)
    writeInt32BE(frame, 0, HTX_MAGIC)
    payload.copyInto(frame, 4)
    writeInt32BE(frame, 4 + payload.size, crc.toInt())
    return frame
}

/** Deserialize an HtxMessage from a framed binary format, verifying CRC32. */
fun HtxMessage.Companion.deserialize(bytes: ByteArray): HtxMessage? {
    if (bytes.size < 8) return null // magic(4) + crc(4) minimum
    // Verify magic
    val magic = readInt32BE(bytes, 0)
    if (magic != HTX_MAGIC) return null
    // Extract payload and CRC
    val payloadLen = bytes.size - 8
    val payload = bytes.copyOfRange(4, 4 + payloadLen)
    val storedCrc = readInt32BE(bytes, 4 + payloadLen).toUInt()
    // Verify checksum
    val computedCrc = HtxCrc32.compute(payload)
    if (storedCrc != computedCrc) return null
    // Parse payload
    return parsePayload(payload)
}

// ── Internal payload encoding helpers ────────────────────────────
fun HtxMessage.buildPayload(): ByteArray {
    val blocks = this.blocks
    // flags(4) + blockCount(2) + blocks...
    val parts = LinkedList<ByteArray>()
    // flags (big-endian uint32)
    val flagBuf = ByteArray(4)
    writeInt32BE(flagBuf, 0, flags.toInt())
    parts.add(flagBuf)
    // blockCount (big-endian uint16)
    val countBuf = ByteArray(2)
    countBuf[0] = ((blocks.size shr 8) and 0xFF).toByte()
    countBuf[1] = (blocks.size and 0xFF).toByte()
    parts.add(countBuf)
    // Each block
    for (b in blocks) {
        parts.add(encodeBlock(b))
    }
    // Flatten
    val total = parts.sumOf { it.size }
    val result = ByteArray(total)
    var pos = 0
    for (p in parts) {
        p.copyInto(result, pos)
        pos += p.size
    }
    return result
}
fun encodeBlock(bd: HtxBlockData): ByteArray {
    val data = when (bd) {
        is HtxBlockData.StartLine -> encodeStartLine(bd)
        is HtxBlockData.Header -> encodeHeader(bd)
        is HtxBlockData.Data -> encodeDataBlock(bd)
        is HtxBlockData.Trailer -> encodeTrailerData(bd)
        is HtxBlockData.EndHeaders -> byteArrayOf()
        is HtxBlockData.EndTrailers -> byteArrayOf()
    }
    // type(1) + dataLen(2) + data
    val typeCode = bd.blockType.code
    val result = ByteArray(3 + data.size)
    result[0] = typeCode.toByte()
    result[1] = ((data.size shr 8) and 0xFF).toByte()
    result[2] = (data.size and 0xFF).toByte()
    data.copyInto(result, 3)
    return result
}
fun encodeStartLine(sl: HtxBlockData.StartLine): ByteArray {
    val s = sl.sl
    val parts = LinkedList<ByteArray>()
    parts.add(byteArrayOf(if (s.isRequest) 1.toByte() else 0.toByte()))
    if (s.isRequest) {
        // method(1) + uriLen(2) + uri + verMajor(1) + verMinor(1)
        val methodCode = (s.method?.ordinal ?: 0).toByte()
        parts.add(byteArrayOf(methodCode))
        val uri = s.uri
        val uriLen = ByteArray(2)
        uriLen[0] = ((uri.size shr 8) and 0xFF).toByte()
        uriLen[1] = (uri.size and 0xFF).toByte()
        parts.add(uriLen)
        parts.add(uri)
        parts.add(byteArrayOf(s.version.first.toByte(), s.version.second.toByte()))
    } else {
        // status(2) + reasonLen(2) + reason + verMajor(1) + verMinor(1)
        val st = s.status ?: 200
        val statusBuf = ByteArray(2)
        statusBuf[0] = ((st shr 8) and 0xFF).toByte()
        statusBuf[1] = (st and 0xFF).toByte()
        parts.add(statusBuf)
        val reason = s.reason
        val reasonLen = ByteArray(2)
        reasonLen[0] = ((reason.size shr 8) and 0xFF).toByte()
        reasonLen[1] = (reason.size and 0xFF).toByte()
        parts.add(reasonLen)
        parts.add(reason)
        parts.add(byteArrayOf(s.version.first.toByte(), s.version.second.toByte()))
    }
    val total = parts.sumOf { it.size }
    val result = ByteArray(total)
    var pos = 0
    for (p in parts) {
        p.copyInto(result, pos)
        pos += p.size
    }
    return result
}
fun encodeHeader(hdr: HtxBlockData.Header): ByteArray {
    val total = 2 + hdr.name.size + 2 + hdr.value.size
    val result = ByteArray(total)
    var pos = 0
    result[pos++] = ((hdr.name.size shr 8) and 0xFF).toByte()
    result[pos++] = (hdr.name.size and 0xFF).toByte()
    hdr.name.copyInto(result, pos); pos += hdr.name.size
    result[pos++] = ((hdr.value.size shr 8) and 0xFF).toByte()
    result[pos++] = (hdr.value.size and 0xFF).toByte()
    hdr.value.copyInto(result, pos)
    return result
}
fun encodeDataBlock(d: HtxBlockData.Data): ByteArray = d.bytes
fun encodeTrailerData(t: HtxBlockData.Trailer): ByteArray = encodeHeader(HtxBlockData.Header(t.name, t.value))

// ── Internal payload decoding helpers ────────────────────────────
fun HtxMessage.Companion.parsePayload(payload: ByteArray): HtxMessage? {
    if (payload.size < 6) return null // flags(4) + count(2) minimum
    var pos = 0
    val flags = readInt32BE(payload, pos).toUInt(); pos += 4
    val blockCount = ((payload[pos].toInt() and 0xFF) shl 8) or (payload[pos + 1].toInt() and 0xFF); pos += 2
    val msg = HtxMessage(flags = flags)
    for (i in 0 until blockCount) {
        if (pos + 3 > payload.size) return null
        val typeCode = payload[pos].toUByte(); pos++
        val dataLen = ((payload[pos].toInt() and 0xFF) shl 8) or (payload[pos + 1].toInt() and 0xFF); pos += 2
        if (pos + dataLen > payload.size) return null
        val data = payload.copyOfRange(pos, pos + dataLen); pos += dataLen
        val bd = decodeBlock(typeCode, data) ?: return null
        msg.blocks.add(bd)
    }
    return msg
}
fun decodeBlock(typeCode: UByte, data: ByteArray): HtxBlockData? {
    val type = HtxBlockType.fromCode(typeCode)
    return when (type) {
        HtxBlockType.ReqSl, HtxBlockType.ResSl -> decodeStartLine(type, data)
        HtxBlockType.Hdr -> decodeHeaderBlock(data)
        HtxBlockType.Data -> HtxBlockData.Data(data)
        HtxBlockType.Tlr -> decodeTrailerBlock(data)
        HtxBlockType.Eoh -> HtxBlockData.EndHeaders
        HtxBlockType.Eot -> HtxBlockData.EndTrailers
        HtxBlockType.Unused -> null
        HtxBlockType.DHTX_REQ, HtxBlockType.DHTX_RES -> null
    }
}
fun decodeStartLine(type: HtxBlockType, data: ByteArray): HtxBlockData? {
    if (data.isEmpty()) return null
    val isReq = data[0].toInt() != 0
    var pos = 1
    return if (isReq) {
        if (pos + 5 > data.size) return null
        val methodOrd = data[pos].toInt() and 0xFF; pos++
        val uriLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF); pos += 2
        if (pos + uriLen > data.size) return null
        val uri = data.copyOfRange(pos, pos + uriLen); pos += uriLen
        val verMajor = data[pos].toInt() and 0xFF; pos++
        val verMinor = data[pos].toInt() and 0xFF
        val method = HttpMethod.entries.getOrNull(methodOrd) ?: HttpMethod.Unknown
        HtxBlockData.StartLine(HtxStartLine.request(method, uri, verMajor, verMinor))
    } else {
        if (pos + 6 > data.size) return null
        val status = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF); pos += 2
        val reasonLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF); pos += 2
        if (pos + reasonLen > data.size) return null
        val reason = data.copyOfRange(pos, pos + reasonLen); pos += reasonLen
        val verMajor = data[pos].toInt() and 0xFF; pos++
        val verMinor = data[pos].toInt() and 0xFF
        HtxBlockData.StartLine(HtxStartLine.response(status, reason, verMajor, verMinor))
    }
}
fun decodeHeaderBlock(data: ByteArray): HtxBlockData? {
    if (data.size < 4) return null
    var pos = 0
    val nameLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF); pos += 2
    if (pos + nameLen > data.size) return null
    val name = data.copyOfRange(pos, pos + nameLen); pos += nameLen
    val valueLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF); pos += 2
    if (pos + valueLen > data.size) return null
    val value = data.copyOfRange(pos, pos + valueLen)
    return HtxBlockData.Header(name, value)
}
fun decodeTrailerBlock(data: ByteArray): HtxBlockData? {
    val hdr = decodeHeaderBlock(data) as? HtxBlockData.Header ?: return null
    return HtxBlockData.Trailer(hdr.name, hdr.value)
}

// ── Big-endian primitives ────────────────────────────────────────
fun writeInt32BE(buf: ByteArray, offset: Int, value: Int) {
    buf[offset]     = ((value ushr 24) and 0xFF).toByte()
    buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
    buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
    buf[offset + 3] = (value and 0xFF).toByte()
}
fun readInt32BE(buf: ByteArray, offset: Int): Int =
    ((buf[offset].toInt() and 0xFF) shl 24) or
    ((buf[offset + 1].toInt() and 0xFF) shl 16) or
    ((buf[offset + 2].toInt() and 0xFF) shl 8) or
    (buf[offset + 3].toInt() and 0xFF)

fun HtxMessage.toHttp1(): ByteArray {
    val sb = StringBuilder()
    val sl = this.startLine() ?: return byteArrayOf()
    if (sl.isRequest) {
        val methodName = sl.method?.name?.uppercase() ?: ""
        val uri = sl.uri.decodeToString()
        val ver = "${sl.version.first}.${sl.version.second}"
        sb.append("$methodName $uri HTTP/$ver\r\n")
    } else {
        val ver = "${sl.version.first}.${sl.version.second}"
        val status = sl.status ?: 200
        val reason = sl.reason.decodeToString()
        sb.append("HTTP/$ver $status $reason\r\n")
    }

    val hdrs = headers(this).toList()
    val dataList = dataBlocks(this).toList()
    val bodyLen = dataList.sumOf { it.bytes.size }
    val hasContentLength = hdrs.any { it.first.decodeToString().equals("Content-Length", ignoreCase = true) }

    for ((name, value) in hdrs) {
        sb.append("${name.decodeToString()}: ${value.decodeToString()}\r\n")
    }
    if (bodyLen > 0 && !hasContentLength) {
        sb.append("Content-Length: $bodyLen\r\n")
    }
    sb.append("\r\n")

    val headerBytes = sb.toString().encodeToByteArray()
    if (bodyLen == 0) return headerBytes

    val out = ByteArray(headerBytes.size + bodyLen)
    headerBytes.copyInto(out, 0)
    var pos = headerBytes.size
    for (d in dataList) {
        d.bytes.copyInto(out, pos)
        pos += d.bytes.size
    }
    return out
}

fun parseHttp1(bytes: ByteArray): HtxMessage? = HtxMessage.parseHttp1(bytes)

fun normalizeToHtx(bytes: ByteArray): HtxMessage = HtxMessage.normalizeToHtx(bytes)

// ── Transformation algebra ──────────────────────────────────────

fun HtxMessage.mergeTrailers(): HtxMessage {
    val newBlocks = LinkedList<HtxBlockData>()
    for (b in this.blocks) {
        when (b) {
            is HtxBlockData.Trailer -> newBlocks.add(HtxBlockData.Header(b.name, b.value))
            is HtxBlockData.EndTrailers -> {
                // drop
            }
            else -> newBlocks.add(b)
        }
    }
    return HtxMessage(blocks = newBlocks.toMutableList(), flags = this.flags)
}

fun HtxMessage.stripBody(): HtxMessage {
    val newBlocks = this.blocks.filter { it !is HtxBlockData.Data }.toMutableList()
    val newMsg = HtxMessage(blocks = newBlocks, flags = this.flags)
    newMsg.setEom()
    return newMsg
}

fun HtxMessage.withFlag(flag: HtxFlags): HtxMessage {
    return HtxMessage(blocks = this.blocks.toMutableList(), flags = (this.flags or flag.mask))
}

// ── Construction algebra (DSL factories) ────────────────────────

class HtxMessageBuilder {
    val headers = LinkedList<Pair<ByteArray, ByteArray>>()
    fun header(name: ByteArray, value: ByteArray) { headers.add(name to value) }
}

fun request(method: HttpMethod, uri: ByteArray, block: HtxMessageBuilder.() -> Unit = {}): HtxMessage {
    val bld = HtxMessageBuilder().apply(block)
    val msg = HtxMessage()
    msg.addStartLine(HtxStartLine.request(method, uri))
    for ((n, v) in bld.headers) msg.addHeader(n, v)
    msg.addEndHeaders()
    return msg
}

fun response(status: Int, reason: ByteArray, block: HtxMessageBuilder.() -> Unit = {}): HtxMessage {
    val bld = HtxMessageBuilder().apply(block)
    val msg = HtxMessage()
    msg.addStartLine(HtxStartLine.response(status, reason))
    for ((n, v) in bld.headers) msg.addHeader(n, v)
    msg.addEndHeaders()
    return msg
}
