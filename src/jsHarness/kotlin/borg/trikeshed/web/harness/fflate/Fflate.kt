@file:JsModule("fflate")
@file:JsNonModule

package borg.trikeshed.web.harness.fflate

import org.khronos.webgl.Uint8Array

external fun unzipSync(data: Uint8Array, opts: dynamic): dynamic
external fun zipSync(data: dynamic, opts: dynamic): Uint8Array
external fun strToU8(str: String): Uint8Array

external class Unzip(cb: (file: dynamic) -> Unit) {
    fun register(decoder: dynamic)
    fun push(chunk: Uint8Array, final: Boolean)
}

external val UnzipInflate: dynamic
