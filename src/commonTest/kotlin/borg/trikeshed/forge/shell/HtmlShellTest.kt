package borg.trikeshed.forge.shell

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class HtmlShellTest {

    @Test
    fun loadReturnsIndexHtml() {
        val html = HtmlShell.load()
        assertTrue(html.contains("<title>TrikeShed Forge</title>"), "Expected <title>TrikeShed Forge</title>")
    }

    @Test
    fun loadContainsScriptTagVerbatim() {
        val html = HtmlShell.load()
        assertTrue(html.contains("<script src=\"./TrikeShed.js\"></script>"), "Expected <script src=\"./TrikeShed.js\"></script>")
    }

    @Test
    fun cssAssetReturnsAppCss() {
        val css = HtmlShell.cssAsset("app")
        assertTrue(css.contains("#forge-root"), "Expected #forge-root")
    }

    @Test
    fun jsAssetReturnsAppJs() {
        val js = HtmlShell.jsAsset("app")
        assertTrue(js.contains("window.trikeshed.shellVersion = \"1.0.0\""), "Expected window.trikeshed.shellVersion = \"1.0.0\"")
    }

    @Test
    fun cssAssetRejectsUnknownName() {
        val ex = assertFailsWith<IllegalArgumentException> {
            HtmlShell.cssAsset("nonexistent")
        }
        assertTrue(ex.message!!.contains("no css asset: nonexistent"))
    }

    @Test
    fun jsAssetRejectsUnknownName() {
        val ex = assertFailsWith<IllegalArgumentException> {
            HtmlShell.jsAsset("nonexistent")
        }
        assertTrue(ex.message!!.contains("no js asset: nonexistent"))
    }

    @Test
    fun shellAssetRegistryHasIndexPlusBundles() {
        val config = ShellConfig()
        val registry = ShellAssetRegistry(config)
        val assets = registry.requiredAssets()
        assertTrue(assets.contains("index.html"))
        assertTrue(assets.contains("app.css"))
        assertTrue(assets.contains("app.js"))
    }

    @Test
    fun scriptTagForBuildsCorrectTag() {
        val registry = ShellAssetRegistry(ShellConfig())
        val tag = registry.scriptTagFor("TrikeShed.js")
        assertTrue(tag == "<script src=\"./TrikeShed.js\"></script>")
    }

    @Test
    fun shellConfigRejectsBlankTitle() {
        assertFailsWith<IllegalArgumentException> {
            ShellConfig(title = "")
        }
    }

    @Test
    fun shellConfigRejectsBlankBundleName() {
        assertFailsWith<IllegalArgumentException> {
            ShellConfig(scriptBundleName = "")
        }
    }
}
