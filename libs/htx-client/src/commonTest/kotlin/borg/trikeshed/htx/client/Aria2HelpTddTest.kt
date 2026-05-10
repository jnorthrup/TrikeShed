package borg.trikeshed.htx.client

import kotlin.test.Test
import kotlin.test.assertTrue

class Aria2HelpTddTest {
    @Test
    fun helpContainsExpectedOptions() {
        val help = HyperDLHelp.helpText()
        assertTrue(help.contains("-Z"), "help missing -Z")
        assertTrue(help.contains("-c"), "help missing -c")
        assertTrue(help.contains("--save-not-found"), "help missing --save-not-found")
        assertTrue(help.contains("-x"), "help missing -x")
        assertTrue(help.contains("-j"), "help missing -j")
        assertTrue(help.contains("-s"), "help missing -s")
        assertTrue(help.contains("-d"), "help missing -d")
        assertTrue(help.contains("--header"), "help missing --header")
    }
}
