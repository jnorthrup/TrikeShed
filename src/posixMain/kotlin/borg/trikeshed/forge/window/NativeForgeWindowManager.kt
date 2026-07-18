package borg.trikeshed.forge.window

import platform.posix.*
import kotlinx.cinterop.*

/**
 * POSIX-based Window Manager.
 * Serves HTML to a temp file and opens it using the system browser command (`open` or `xdg-open`).
 */
class NativeForgeWindowManager : ForgeWindowManager {
    @OptIn(ExperimentalForeignApi::class)
    override fun launch(html: String) {
        val tempFilePath = "/tmp/forgeApp_native_${getpid()}.html"
        val file = fopen(tempFilePath, "w")
        if (file != null) {
            fputs(html, file)
            fclose(file)

            // Try to open using 'open' (macOS) or 'xdg-open' (Linux)
            if (system("open $tempFilePath") != 0) {
                system("xdg-open $tempFilePath")
            }
        }
    }
}
