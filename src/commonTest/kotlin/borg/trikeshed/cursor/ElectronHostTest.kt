package borg.trikeshed.cursor

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * TDD RED — Electron host test.
 *
 * Goal: prove TrikeShed commonMain tests can run inside an Electron-rendered
 * Chromium (browser() target with karma-electron launcher), not just Node.
 *
 * RED because: at time of writing, the root project only declares `js { nodejs() }`.
 * No `browser()` block, no `useElectron()` call, no karma.conf.js. The Electron
 * `jsBrowserTest` task does not exist and `./gradlew :jsBrowserTest` fails with
 * "Task 'jsBrowserTest' not found in root project 'TrikeShed'".
 *
 * When this test runs under `jsBrowserTest` (Electron + Chromium), it will see
 * `window.process.type === 'renderer'` (Electron renderer process) and assert
 * true. Today it cannot reach that path at all — RED.
 */
class ElectronHostTest {

    @Test
    fun runsInsideElectronRenderer() {
        // The jsBrowserTest target should spawn a karma-electron-launched
        // Chromium with Electron bindings. Detect via the renderer global
        // injected by Electron's preload + contextIsolation=false default.
        val isElectronRenderer = js(
            "typeof process !== 'undefined' " +
            "&& process.versions != null " +
            "&& process.versions.electron != null " +
            "&& process.type === 'renderer'"
        ) as Boolean
        assertTrue(isElectronRenderer, "expected to run inside Electron renderer process")
    }

    @Test
    fun hasChromiumNavigator() {
        // In Electron renderer, navigator.userAgent contains "Electron/<ver>".
        val ua = js("typeof navigator !== 'undefined' ? navigator.userAgent : ''") as String
        assertTrue(ua.contains("Electron/", ignoreCase = true), "expected Electron userAgent, got: $ua")
    }
}
