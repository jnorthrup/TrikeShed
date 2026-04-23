package borg.trikeshed.couch.miniduck

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

object MiniDuckBlockFiles {
    fun write(path: Path, block: BlockRowVec) {
        check(block.state == BlockRowVec.State.SEALED) { "MiniDuck block files are written only after sealing" }
        Files.writeString(
            path,
            MiniDuckBlockCodec.encode(block),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    fun read(path: Path): BlockRowVec =
        MiniDuckBlockCodec.decode(Files.readString(path, StandardCharsets.UTF_8))
}
