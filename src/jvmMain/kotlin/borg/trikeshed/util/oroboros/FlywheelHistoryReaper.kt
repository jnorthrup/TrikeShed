package borg.trikeshed.util.oroboros

import java.io.File

object FlywheelHistoryReaper {

    fun reapOldTags(repoDir: File, keepLatest: Int = 50) {
        try {
            val process = ProcessBuilder("git", "-C", repoDir.path, "tag", "-l", "--sort=-creatordate", "flywheel/jules-*").start()
            val tags = process.inputStream.bufferedReader().readLines()
            if (process.waitFor() != 0 || tags.size <= keepLatest) return

            val tagsToDelete = tags.drop(keepLatest)
            for (tag in tagsToDelete) {
                ProcessBuilder("git", "-C", repoDir.path, "tag", "-d", tag).start().waitFor()
                ProcessBuilder("git", "-C", repoDir.path, "push", "origin", "--delete", tag).start().waitFor()
            }
        } catch (e: Exception) {
            // Best effort; silently ignore
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val keep = args.firstOrNull()?.toIntOrNull() ?: 50
        reapOldTags(File(System.getProperty("user.dir")), keep)
    }
}
