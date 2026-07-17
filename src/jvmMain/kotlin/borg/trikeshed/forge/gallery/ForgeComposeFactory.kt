package borg.trikeshed.forge.gallery

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.supervisorScope
import kotlin.jvm.JvmStatic

/**
 * Factory for creating the Compose Desktop window.
 * Lives in jvmMain so the JS/WASM targets don't pull in Compose dependencies.
 * The actual Forge workspace shell is in commonMain (ForgeWorkspace).
 */
object ForgeComposeFactory {

    /**
     * Creates and shows the Forge JVM window.
     * TODO: Implement the actual window creation and ForgeWorkspace mounting.
     */
    @JvmStatic
    fun createAndShow(): Window = TODO("Implement Forge JVM Compose Desktop shell")
}