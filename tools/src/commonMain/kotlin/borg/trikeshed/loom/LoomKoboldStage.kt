package borg.trikeshed.loom

import borg.trikeshed.userspace.*
import borg.trikeshed.userspace.UringOp.Companion.Submissions

/**
 * cocaine-rats `crates/loomctl/src/bin/loom-kobold-stage.rs`: offline staging only, no credentials
 * are loaded and no network call is made. Failures exit 1 behind `loom-kobold-stage: `.
 */
fun loomKoboldStage(args: Array<String>) {
    val ring = Config.ring()

    /** A private regular file read through the descriptor whose inode and device the unfollowed path named. */
    fun private_input(path: String): ByteArray {
        val before = runCatching { ring.symlink_metadata(path) }.getOrNull() ?: error("protected input unavailable")
        if (before.stx_mode and UringOp.S_IFMT != UringOp.S_IFREG || before.stx_mode and 0x3f != 0) error("input must be a private regular file")
        val fd = runCatching { ring.open(path, UringOp.O_RDONLY, 0) }.getOrElse { error("protected input unavailable") }
        try {
            val after = runCatching { ring.metadata(fd) }.getOrElse { error("protected input unavailable") }
            if (before.stx_ino != after.stx_ino || before.stx_dev_major != after.stx_dev_major || before.stx_dev_minor != after.stx_dev_minor ||
                after.stx_mode and UringOp.S_IFMT != UringOp.S_IFREG || after.stx_mode and 0x3f != 0
            ) error("protected input changed during open")
            val bytes = runCatching { ring.read_to_end(fd, Kobold.MAX_SNAPSHOT_BYTES + 1) }.getOrElse { error("protected input read failed") }
            if (bytes.size > Kobold.MAX_SNAPSHOT_BYTES) error("protected input exceeds size limit")
            return bytes
        } finally {
            ring.errno { Submissions.close(fd, 1) }
        }
    }

    fun write_private(path: String, bytes: ByteArray) {
        val fd = runCatching { ring.open(path, UringOp.O_WRONLY or UringOp.O_CREAT or UringOp.O_EXCL, 0x180) }
            .getOrElse { error("private output already exists or cannot be created") }
        try {
            runCatching {
                ring.write_all(fd, bytes)
                ring.sync_all(fd)
            }.getOrElse { error("private output publication failed") }
        } finally {
            ring.errno { Submissions.close(fd, 1) }
        }
    }

    /** `serde_json::to_vec_pretty` and a newline. */
    fun json_bytes(value: Any): ByteArray = (jsonPretty(value) + "\n").encodeToByteArray()

    fun stage_dir(path: String) {
        val meta = runCatching { ring.symlink_metadata(path) }.getOrNull() ?: error("stage directory unavailable")
        if (meta.stx_mode and UringOp.S_IFMT != UringOp.S_IFDIR || meta.stx_mode and 0x3f != 0) error("stage must be a private directory")
    }

    fun load(path: String): Kobold.PreparedOverride {
        stage_dir(path)
        val manifest = Kobold.parse(private_input(join(path, "manifest.json")))
        val target = Kobold.Target.parse(manifest["profile"] as? String ?: error("stage profile missing"))
        val target_id = manifest["target_id"] as? String ?: error("stage endpoint identity missing")
        val source = private_input(join(path, "source.json"))
        val stage = Kobold.prepare(target, target_id, source)
        if (!same(manifest, stage.manifest())) error("stage identity or complete source digest differs")
        val patch = Kobold.parse(private_input(join(path, "patch.json")))
        val rollback = Kobold.parse(private_input(join(path, "rollback.json")))
        if (!same(patch, stage.patch) || !same(rollback, stage.rollback)) error("saved patch or rollback differs from reviewed generation")
        return stage
    }

    try {
        val summary = when {
            args.size == 5 && args[0] == "prepare" -> {
                val target = Kobold.Target.parse(args[1])
                val target_id = args[2]
                val source = private_input(args[3])
                val stage = Kobold.prepare(target, target_id, source)
                val out = args[4]
                if (!runCatching { ring.create_dir(out, 0x1c0) }.getOrDefault(false)) error("new private stage directory required")
                write_private(join(out, "source.json"), source)
                val patch = json_bytes(stage.patch)
                write_private(join(out, "patch.json"), patch)
                write_private(join(out, "rollback.json"), json_bytes(stage.rollback))
                write_private(join(out, "manifest.json"), json_bytes(stage.manifest()))
                runCatching { ring.sync_path(out) }.getOrElse { error("private directory publication failed") }
                mapOf(
                    "status" to "staged_not_applied", "target_id" to target_id, "profile" to target.profile, "patch_bytes" to patch.size.toLong(),
                    "adapter_bytes" to stage.manifest().getValue("adapter_bytes"), "adapter_sha256" to stage.manifest().getValue("adapter_sha256"),
                )
            }
            args.size == 3 && args[0] in setOf("precondition", "readback", "rollback-readback") -> {
                val stage = load(args[1])
                val observed = private_input(args[2])
                when (args[0]) {
                    "precondition" -> stage.verifyPrecondition(observed)
                    "readback" -> stage.verifyReadback(observed)
                    else -> stage.verifyRollback(observed)
                }
                mapOf("status" to "verified", "check" to args[0], "target_id" to stage.manifest().getValue("target_id"))
            }
            else -> error("usage: loom-kobold-stage prepare <48gb-mtp|96gb-batch> <exact-endpoint-id> <private-v2-get.json> <new-stage-dir>; or <precondition|readback|rollback-readback> <stage-dir> <private-v2-get.json>")
        }
        println(jsonText(summary))
    } finally {
        ring.closeNow()
    }
}
