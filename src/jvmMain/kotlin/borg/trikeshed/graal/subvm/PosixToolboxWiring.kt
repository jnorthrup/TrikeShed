package borg.trikeshed.graal.subvm

import borg.trikeshed.vm.Teleported

/**
 * Register the minimalist POSIX tool emulations as host delegates on a guest isolate.
 *
 * Guest code calls `host.call('cat', '/workspace/x')` (or any of [PosixToolbox.tools]);
 * the crossing is the ordinary Teleported boundary, so every tool call is a receipt on the
 * blackboard like any other host delegate. Result shape: `{exit, stdout, stderr}`.
 */
fun GuestIsolate.installPosixTools(fs: PosixFs) {
    for (tool in PosixToolbox.tools) {
        delegate(tool) { args ->
            val argv = listOf(tool) + args.map { (it as? Teleported.Str)?.v ?: it.toString() }
            val r = PosixToolbox.run(fs, argv)
            Teleported.obj("exit" to r.exit, "stdout" to r.stdout, "stderr" to r.stderr)
        }
    }
}

/**
 * GraalBtrfsSupervisor convenience: the POSIX toolbox over the supervisor's own live subvolume.
 * Called by [Hypervisor.spawn] for world isolates so every btrfs-world guest boots with
 * cat/ls/grep/cp/mv/rm/mkdir/echo/wc/head/tail/touch/pwd available as host delegates.
 */
fun GraalBtrfsSupervisor.installPosixTools() {
    installPosixTools(BtrfsPosixFs(vfs.btrfsForTools(), vfs.liveSubvolumeForTools()))
}
