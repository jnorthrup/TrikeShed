package borg.trikeshed.forge.shell

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class HtmlShellTest {

    @Test
    fun loadReturnsIndexHtml() {
        val html = HtmlShell.load()
        assertTrue(html.contains("<title>TrikeShed Forge</title>"), "load() missing title")
    }

    @Test
    fun loadContainsScriptTagVerbatim() {
        val html = HtmlShell.load()
        assertTrue(html.contains("<script src=\"./TrikeShed.js\"></script>"), "load() missing script tag")
    }

    @Test
    fun cssAssetReturnsAppCss() {
        val css = HtmlShell.cssAsset("app")
        assertTrue(css.contains("#forge-root"), "cssAsset(\"app\") missing #forge-root")
    }

    @Test
    fun jsAssetReturnsAppJs() {
        val js = HtmlShell.jsAsset("app")
        assertTrue(js.contains("window.trikeshed.shellVersion = \"1.0.0\""), "jsAsset(\"app\") missing shellVersion")
    }

    @Test
    fun cssAssetRejectsUnknownName() {
        assertFailsWith<IllegalArgumentException> {
            HtmlShell.cssAsset("nonexistent")
        }
    }

    @Test
    fun jsAssetRejectsUnknownName() {
        assertFailsWith<IllegalArgumentException> {
            HtmlShell.jsAsset("nonexistent")
        }
    }

    @Test
    fun shellAssetRegistryHasIndexPlusBundles() {
        val config = ShellConfig()
        val registry = ShellAssetRegistry(config)
        val assets = registry.requiredAssets()
        assertTrue(assets.contains("index.html"), "Missing index.html")
        assertTrue(assets.contains("app.css"), "Missing app.css")
        assertTrue(assets.contains("app.js"), "Missing app.js")
    }

    @Test
    fun scriptTagForBuildsCorrectTag() {
        val config = ShellConfig()
        val registry = ShellAssetRegistry(config)
        val tag = registry.scriptTagFor("TrikeShed.js")
        assertTrue(tag == "<script src=\"./TrikeShed.js\"></script>", "Incorrect script tag format")
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
