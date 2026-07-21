package borg.trikeshed.fetch

import borg.trikeshed.platform.writeToStdOut
import borg.trikeshed.platform.writeToStdErr

/**
 * Handles the program invocation when it's called as 'trike-aria2c'.
 * This is a basic stub implementation.
 *
 * @param args Command line arguments passed to 'trike-aria2c'.
 * @return An exit code (0 for success, non-zero for failure).
 */
suspend fun handleAria2cInvocation(args: List<String>): Int {
    writeToStdOut("trike-aria2c mode activated (stub implementation).\n")
    if (args.isEmpty()) {
        writeToStdErr("trikeshed-aria2c: No arguments provided.\n")
        writeToStdOut("Usage: trike-aria2c [options] [URL1 URL2 ...]\n")
        return 1
    }

    writeToStdOut("Arguments received:\n")
    args.forEach { arg ->
        writeToStdOut("  $arg\n")
    }

    writeToStdOut("\nSimulating download of URLs (not actually downloading):\n")
    args.filter { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("ftp://") }
        .forEach { url ->
            writeToStdOut("  Pretending to download $url ... Done.\n")
        }
    
    writeToStdOut("\nAria2c functionality is not fully implemented in this version.\n")
    return 0 // Default success for stub
}
