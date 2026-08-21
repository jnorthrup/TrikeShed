package modelmux

import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import kotlinx.browser.window

actual val defaultSecureIdGenerator: SecureIdGenerator = object : SecureIdGenerator {
    override fun generateHexId(prefix: String, byteLength: Int): String {
        val array = Uint8Array(byteLength)
        window.asDynamic().crypto.getRandomValues(array)
        val bytes = ByteArray(byteLength) { i -> array[i] }
        val hex = bytes.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        return "$prefix-$hex"
    }
}
