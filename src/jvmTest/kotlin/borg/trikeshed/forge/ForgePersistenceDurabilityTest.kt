package borg.trikeshed.forge

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class ForgePersistenceDurabilityTest {
    @Test
    fun generatedWorkspaceScriptLoadsAndSavesTheSameLocalKey() {
        val script = forgePersistenceScript()

        assertContains(script, "const LS_KEY = 'forge.workspace.v2'")
        assertContains(script, "localStorage.getItem(LS_KEY)")
        assertContains(script, "localStorage.setItem(LS_KEY, JSON.stringify(state))")
        assertTrue(script.indexOf("function loadState()") < script.indexOf("let state = loadState()"))
    }

    @Test
    fun everyWorkspaceMutationPersistsBeforeItEnqueuesACommand() {
        val script = forgePersistenceScript()
        val mutate = script.substringAfter("function mutate(updater)").substringBefore("// ── Element refs")

        assertTrue(mutate.indexOf("updater(state)") < mutate.indexOf("saveState()"))
        assertTrue(mutate.indexOf("saveState()") < mutate.indexOf("__forgeCommandQueue.push"))
    }
}
