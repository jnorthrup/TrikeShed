package borg.trikeshed.parse.parquet

import java.util.zip.GZIPInputStream

/** What commonMain does not decompress: GZIP, through the JDK. */
val parquetInflate: Inflate = { codec, bytes, _ ->
    check(codec == 2) { "parquet codec $codec is not read" }
    GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
}
