package borg.trikeshed.media

/**
 * The OCR pre-pass (ffmpeg `format=gray,eq=contrast=1.5:brightness=0.1`) over RGBA bytes, in place — what
 * run_tika.sh and JvmTikaIngestAdapter do through ffmpeg, for a canvas ImageData where there is no ffmpeg.
 */
fun ByteArray.ocrPrepassRgba(): ByteArray {
    var i = 0
    while (i + 3 < size) {
        val y = 0.299 * (this[i].toInt() and 0xFF) + 0.587 * (this[i + 1].toInt() and 0xFF) + 0.114 * (this[i + 2].toInt() and 0xFF)
        val v = ((y - 128) * 1.5 + 128 + 25.5).toInt().coerceIn(0, 255).toByte()
        this[i] = v; this[i + 1] = v; this[i + 2] = v; i += 4
    }
    return this
}
