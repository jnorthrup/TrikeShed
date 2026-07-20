package borg.trikeshed.forge.shell

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class HtmlShellTest {
<<<<<<< HEAD

    @Test
    fun loadReturnsIndexHtml() {
        val html = HtmlShell.load()
        assertTrue(html.contains("<title>TrikeShed Forge</title>"), "load() missing title")
=======
    @Test
    fun loadReturnsIndexHtml() {
        val html = HtmlShell.load()
        assertTrue(html.contains("<title>TrikeShed Forge</title>"), "Expected <title>TrikeShed Forge</title>")
>>>>>>> origin/add-html-shell-assets-10555369453043646034
    }

    @Test
    fun loadContainsScriptTagVerbatim() {
        val html = HtmlShell.load()
<<<<<<< HEAD
        assertTrue(html.contains("<script src=\"./TrikeShed.js\"></script>"), "load() missing script tag")
=======
        assertTrue(html.contains("<script src=\"./TrikeShed.js\"></script>"), "Expected <script src=\"./TrikeShed.js\"></script>")
>>>>>>> origin/add-html-shell-assets-10555369453043646034
    }

    @Test
    fun cssAssetReturnsAppCss() {
        val css = HtmlShell.cssAsset("app")
<<<<<<< HEAD
        assertTrue(css.contains("#forge-root"), "cssAsset(\"app\") missing #forge-root")
=======
        assertTrue(css.contains("#forge-root"), "Expected #forge-root")
>>>>>>> origin/add-html-shell-assets-10555369453043646034
    }

    @Test
    fun jsAssetReturnsAppJs() {
        val js = HtmlShell.jsAsset("app")
<<<<<<< HEAD
        assertTrue(js.contains("window.trikeshed.shellVersion = \"1.0.0\""), "jsAsset(\"app\") missing shellVersion")
=======
        assertTrue(js.contains("window.trikeshed.shellVersion = \"1.0.0\""), "Expected window.trikeshed.shellVersion = \"1.0.0\"")
>>>>>>> origin/add-html-shell-assets-10555369453043646034
    }

    @Test
    fun cssAssetRejectsUnknownName() {
<<<<<<< HEAD
        assertFailsWith<IllegalArgumentException> {
            HtmlShell.cssAsset("nonexistent")
        }
=======
        val ex = assertFailsWith<IllegalArgumentException> {
            HtmlShell.cssAsset("nonexistent")
        }
        assertTrue(ex.message!!.contains("no css asset: nonexistent"))
>>>>>>> origin/add-html-shell-assets-10555369453043646034
    }

    @Test
    fun jsAssetRejectsUnknownName() {
<<<<<<< HEAD
        assertFailsWith<IllegalArgumentException> {
            HtmlShell.jsAsset("nonexistent")
        }
=======
        val ex = assertFailsWith<IllegalArgumentException> {
            HtmlShell.jsAsset("nonexistent")
        }
        assertTrue(ex.message!!.contains("no js asset: nonexistent"))
>>>>>>> origin/add-html-shell-assets-10555369453043646034
    }

    @Test
    fun shellAssetRegistryHasIndexPlusBundles() {
        val config = ShellConfig()
        val registry = ShellAssetRegistry(config)
        val assets = registry.requiredAssets()
<<<<<<< HEAD
        assertTrue(assets.contains("index.html"), "Missing index.html")
        assertTrue(assets.contains("app.css"), "Missing app.css")
        assertTrue(assets.contains("app.js"), "Missing app.js")
=======
        assertTrue(assets.contains("index.html"))
        assertTrue(assets.contains("app.css"))
        assertTrue(assets.contains("app.js"))
>>>>>>> origin/add-html-shell-assets-10555369453043646034
    }

    @Test
    fun scriptTagForBuildsCorrectTag() {
<<<<<<< HEAD
        val config = ShellConfig()
        val registry = ShellAssetRegistry(config)
        val tag = registry.scriptTagFor("TrikeShed.js")
        assertTrue(tag == "<script src=\"./TrikeShed.js\"></script>", "Incorrect script tag format")
=======
        val registry = ShellAssetRegistry(ShellConfig())
        val tag = registry.scriptTagFor("TrikeShed.js")
        assertTrue(tag == "<script src=\"./TrikeShed.js\"></script>")
>>>>>>> origin/add-html-shell-assets-10555369453043646034
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
