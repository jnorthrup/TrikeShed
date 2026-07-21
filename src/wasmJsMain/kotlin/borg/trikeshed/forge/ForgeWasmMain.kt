@file:OptIn(ExperimentalJsExport::class)

package borg.trikeshed.forge

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.js.JsName

/**
 * WASM/JS Browser entry point for Forge workspace.
 * Returns HTML string for JS to render after WASM loads.
 */
@JsExport
@JsName("getForgeHtml")
fun getForgeHtml(): String = ForgeApp.renderHtml()
