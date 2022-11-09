package borg.trikeshed.common

import java.io.File

/** emulates shell command*/
actual fun  mktemp(): String {
    //java mktemp
    val createTempFile = File.createTempFile("tmp", ".tmp", System.getProperty("java.io.tmpdir")?.let { File(it) })
    return createTempFile.absolutePath



}

actual fun  rm(path: String): Boolean {
    //nio files delete
    return File(path).delete()

}

actual fun  mkdir(path: String): Boolean {
    //mkdirhier in java
    return File(path).mkdirs()
}