package borg.trikeshed.loom

/**
 * cocaine-rats `crates/loomctl/src/bin/loom-kobold-stage.rs`: offline staging only, no credentials
 * are loaded and no network call is made. Failures exit 1 behind `loom-kobold-stage: `.
 */
fun loomKoboldStage(args: Array<String>) {
    when {
        args.size == 5 && args[0] == "prepare" -> {
            Kobold.Target.parse(args[1])
            TODO("loom-kobold-stage prepare waits on private_input (lstat and fstat mode, inode and device), which the NIO facade rejects by policy (Files.kt, M3b), and on mkdirat with mode 0700 for the new stage directory")
        }
        args.size == 3 && args[0] in setOf("precondition", "readback", "rollback-readback") ->
            TODO("loom-kobold-stage ${args[0]} waits on stage_dir and private_input (lstat and fstat mode, inode and device), which the NIO facade rejects by policy (Files.kt, M3b)")
        else -> error("usage: loom-kobold-stage prepare <48gb-mtp|96gb-batch> <exact-endpoint-id> <private-v2-get.json> <new-stage-dir>; or <precondition|readback|rollback-readback> <stage-dir> <private-v2-get.json>")
    }
}
