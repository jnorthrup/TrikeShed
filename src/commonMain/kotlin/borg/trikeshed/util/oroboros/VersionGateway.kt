package borg.trikeshed.util.oroboros

import borg.trikeshed.userspace.nio.channels.spi.ProcessOperations

class VersionGateway(
    private val processOps: ProcessOperations,
    private val usePijul: Boolean = false
) {
    suspend fun recordVersion(dir: String, message: String): Boolean {
        if (usePijul) {
            val addRes = processOps.exec(
                command = "pijul",
                args = listOf("add", "--repository", dir, ".")
            )
            if (addRes.exitCode != 0) return false

            val res = processOps.exec(
                command = "pijul",
                args = listOf("record", "--repository", dir, "-a", "-m", message)
            )
            return res.exitCode == 0
        } else {
            val addRes = processOps.exec(
                command = "git",
                args = listOf("-C", dir, "add", ".")
            )
            if (addRes.exitCode != 0) return false

            val commitRes = processOps.exec(
                command = "git",
                args = listOf(
                    "-C", dir,
                    "-c", "user.name=oroboros",
                    "-c", "user.email=agent@trikeshed.borg",
                    "commit", "-m", message
                )
            )
            return commitRes.exitCode == 0
        }
    }

    suspend fun initRepo(dir: String): Boolean {
        if (usePijul) {
            val res = processOps.exec(
                command = "pijul",
                args = listOf("init", "--repository", dir)
            )
            return res.exitCode == 0
        } else {
            val res = processOps.exec(
                command = "git",
                args = listOf("-C", dir, "init")
            )
            return res.exitCode == 0
        }
    }
}
