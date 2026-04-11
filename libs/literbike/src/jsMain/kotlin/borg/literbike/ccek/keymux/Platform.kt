package borg.literbike.ccek.keymux

actual object Platform {
    actual fun getenv(key: String): String? = null // JS has no env vars
}
