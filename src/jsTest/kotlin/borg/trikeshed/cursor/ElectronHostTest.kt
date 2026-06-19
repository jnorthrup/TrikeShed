package borg.trikeshed.cursor

import kotlin.test.Test
import kotlin.test.assertTrue

class ElectronHostTest {

    @Test
    fun runsInsideElectronRenderer() {
        val isElectron = js(
            "typeof process !== 'undefined' " +
            "&& process.versions != null " +
            "&& process.versions.electron != null"
        ) as Boolean
        if (!isElectron) {
            println("Skipping runsInsideElectronRenderer - not running in Electron")
            return
        }
        val isElectronRenderer = js("process.type === 'renderer'") as Boolean
        assertTrue(isElectronRenderer, "expected to run inside Electron renderer process")
    }

    @Test
    fun hasChromiumNavigator() {
        val isElectron = js(
            "typeof process !== 'undefined' " +
            "&& process.versions != null " +
            "&& process.versions.electron != null"
        ) as Boolean
        if (!isElectron) {
            println("Skipping hasChromiumNavigator - not running in Electron")
            return
        }
        val ua = js("typeof navigator !== 'undefined' ? navigator.userAgent : ''") as String
        assertTrue(ua.contains("Electron/", ignoreCase = true), "expected Electron userAgent, got: $ua")
    }
}
