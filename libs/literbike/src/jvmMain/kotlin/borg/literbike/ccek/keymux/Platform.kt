package borg.literbike.ccek.keymux

actual object Platform {
    actual fun getenv(key: String): String? = System.getenv(key)
}
