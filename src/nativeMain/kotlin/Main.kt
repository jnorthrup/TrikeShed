package borg.trikeshed.metal

import kotlinx.cinterop.*
import platform.posix.*

fun main(args: Array<String>) {
    val file: CPointer<FILE>? = fopen("test.txt", "w")
    if (file != null) {
        fputs("Hello, World!", file)
        fclose(file)
    }
}

