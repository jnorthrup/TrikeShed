import re
import json
import os

with open("src/commonMain/kotlin/borg/trikeshed/forge/ForgeApp.kt", "r") as f:
    app_kt = f.read()

# HTML
html_start = app_kt.find('    return """\n') + len('    return """\n')
html_end = app_kt.find('    """.trimIndent()\n}', html_start)
html = app_kt[html_start:html_end]

html = html.replace("${forgeAppStyles()}", "{{STYLES}}")
html = html.replace("${galleryHtml()}", "{{GALLERY}}")
html = html.replace("$seed", "{{SEED}}")
html = html.replace("${forgeAppScript()}", "{{SCRIPT}}")
html = html.replace("</body>", "<script src=\"./TrikeShed.js\"></script>\n</body>")

os.makedirs("src/commonMain/resources/web", exist_ok=True)
with open("src/commonMain/resources/web/index.html", "w") as f:
    f.write(html)

# CSS
style_start = app_kt.find("private fun forgeAppStyles(): String = \"\"\"\n") + len("private fun forgeAppStyles(): String = \"\"\"\n")
style_end = app_kt.find("\"\"\".trimIndent()", style_start)
css = app_kt[style_start:style_end]
with open("src/commonMain/resources/web/styles.css", "w") as f:
    f.write(css)

# JS
with open("src/commonMain/kotlin/borg/trikeshed/forge/ForgePersistenceScript.kt", "r") as f:
    ps_kt = f.read()
script_start = ps_kt.find("fun forgePersistenceScript(): String = \"\"\"\n") + len("fun forgePersistenceScript(): String = \"\"\"\n")
script_end = ps_kt.find("\"\"\".trimIndent()", script_start)
if script_end == -1: script_end = ps_kt.find(".trimIndent()", script_start)
js = ps_kt[script_start:script_end]
with open("src/commonMain/resources/web/script.js", "w") as f:
    f.write(js)

# Gen build.gradle.kts task
with open("build.gradle.kts", "r") as f:
    content = f.read()

addition = """
val generateForgeAssets = tasks.register("generateForgeAssets") {
    group = "build"
    description = "Generates Kotlin strings for Forge web assets"

    val webDir = file("src/commonMain/resources/web")
    val htmlFile = File(webDir, "index.html")
    val cssFile = File(webDir, "styles.css")
    val jsFile = File(webDir, "script.js")

    val outputDir = layout.buildDirectory.dir("generated/source/forgeAssets/kotlin/borg/trikeshed/forge/generated")

    inputs.file(htmlFile)
    inputs.file(cssFile)
    inputs.file(jsFile)
    outputs.dir(outputDir)

    doLast {
        val outDirFile = outputDir.get().asFile
        outDirFile.mkdirs()

        fun createByteArray(name: String, bytes: ByteArray): String {
            val chunks = bytes.toList().chunked(5000)
            for ((i, chunk) in chunks.withIndex()) {
                val code = "package borg.trikeshed.forge.generated\\n\\ninternal object ${name}_$i {\\n" +
                           "    val data: ByteArray = byteArrayOf(\\n" +
                           "        " + chunk.joinToString(",") { it.toString() } + "\\n" +
                           "    )\\n}\\n"
                File(outDirFile, "${name}_$i.kt").writeText(code)
            }

            var code = "package borg.trikeshed.forge.generated\\n\\ninternal object ${name} {\\n"
            code += "    val data: ByteArray get() {\\n"
            code += "        val size = " + bytes.size + "\\n"
            code += "        val arr = ByteArray(size)\\n"
            code += "        var offset = 0\\n"
            for (i in chunks.indices) {
                code += "        ${name}_$i.data.copyInto(arr, offset)\\n"
                code += "        offset += ${chunks[i].size}\\n"
            }
            code += "        return arr\\n"
            code += "    }\\n}\\n"
            File(outDirFile, "${name}.kt").writeText(code)
            return name
        }

        createByteArray("ForgeAssetsHtml", htmlFile.readBytes())
        createByteArray("ForgeAssetsCss", cssFile.readBytes())
        createByteArray("ForgeAssetsJs", jsFile.readBytes())

        File(outDirFile, "ForgeAssets.kt").writeText(
            "package borg.trikeshed.forge.generated\\n\\ninternal object ForgeAssets {\\n" +
            "    val indexHtml: String by lazy { ForgeAssetsHtml.data.decodeToString() }\\n" +
            "    val stylesCss: String by lazy { ForgeAssetsCss.data.decodeToString() }\\n" +
            "    val scriptJs: String by lazy { ForgeAssetsJs.data.decodeToString() }\\n" +
            "}\\n"
        )
    }
}

kotlin {
    sourceSets.getByName("commonMain") {
        kotlin.srcDir(generateForgeAssets.map { it.outputs.files })
    }
}
"""
with open("build.gradle.kts", "w") as f:
    f.write(content + "\n" + addition)


# Update App & Script files
def replace_forge_app_html(content):
    start_idx = content.find("fun forgeAppHtml(): String {")
    end_idx = content.find("private fun forgeAppStyles(): String =", start_idx)
    replacement = """fun forgeAppHtml(): String {
    val baseSeed = defaultForgeAppState().toJsonValue().toMutableMap()
    baseSeed["gallery"] = ForgeGalleryCatalog.toJsonValue()
    baseSeed["blackboard"] = forgeBlackboardSeed()
    val seed = htmlEscape(JsonSupport.stringify(baseSeed))

    return borg.trikeshed.forge.generated.ForgeAssets.indexHtml
        .replace("{{STYLES}}", forgeAppStyles())
        .replace("{{GALLERY}}", galleryHtml())
        .replace("{{SEED}}", seed)
        .replace("{{SCRIPT}}", forgeAppScript())
}

"""
    return content[:start_idx] + replacement + content[end_idx:]

app_kt = replace_forge_app_html(app_kt)

def replace_forge_app_styles(content):
    start_idx = content.find("private fun forgeAppStyles(): String = \"\"\"\n")
    end_idx = content.find("\"\"\".trimIndent()\n", start_idx) + len("\"\"\".trimIndent()\n")
    replacement = "private fun forgeAppStyles(): String = borg.trikeshed.forge.generated.ForgeAssets.stylesCss\n"
    return content[:start_idx] + replacement + content[end_idx:]

app_kt = replace_forge_app_styles(app_kt)

with open("src/commonMain/kotlin/borg/trikeshed/forge/ForgeApp.kt", "w") as f:
    f.write(app_kt)

def replace_ps(content):
    start_idx = content.find("fun forgePersistenceScript(): String = \"\"\"\n")
    end_idx = content.find("\"\"\".trimIndent()\n", start_idx) + len("\"\"\".trimIndent()\n")
    if end_idx == -1 + len("\"\"\".trimIndent()\n"):
        end_idx = content.find(".trimIndent()\n", start_idx) + len(".trimIndent()\n")
    replacement = "fun forgePersistenceScript(): String = borg.trikeshed.forge.generated.ForgeAssets.scriptJs\n"
    return content[:start_idx] + replacement + content[end_idx:]

ps_kt = replace_ps(ps_kt)
with open("src/commonMain/kotlin/borg/trikeshed/forge/ForgePersistenceScript.kt", "w") as f:
    f.write(ps_kt)
