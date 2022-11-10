package borg.trikeshed.common

import borg.trikeshed.common.parser.simple.CharSeries
import kotlinx.cinterop.*
import platform.posix.*

/** lean on getline to read a file into a sequence of CharSeries */
actual fun readLinesSeq(path: String): Sequence<String> = memScoped {
    return sequence {
        val fp = fopen(path, "r")
        if (fp == null) {
            perror("fopen")
            exit(1)
        }

        val line: CPointerVarOf<CPointer<ByteVarOf<Byte>>> = alloc<CPointerVar<ByteVar>>()
        val len: ULongVarOf<size_t> = alloc<size_tVar>()
        len.value = 0u
        var read: ssize_t = 0L

        while (true) {
            read = getline(line.ptr, len.ptr, fp)
            if (read == -1L) break
            yield(/*CharSeries*/(line.value!!.toKString().trim()))
        }
        free(line.value)
        fclose(fp)
        if (ferror(fp) != 0) {
            perror("ferror")
            exit(1)
        }
    }
}


/** lean on getline to read a file into a List of CharSeries */
actual fun readLines(path: String): List<String> = memScoped{
//cinterop as above

    val list = mutableListOf<String>()
    val fp = fopen(path, "r")
    if (fp == null) {
        perror("fopen")
        exit(1)
    }

    val line: CPointerVarOf<CPointer<ByteVarOf<Byte>>> = alloc<CPointerVar<ByteVar>>()
    val len: ULongVarOf<size_t> = alloc<size_tVar>()
    len.value = 0u
    var read: ssize_t = 0L

    while (true) {
        read = getline(line.ptr, len.ptr, fp)
        if (read == -1L) break
        list.add(/*CharSeries*/(line.value!!.toKString().trim()))
    }
    free(line.value)
    fclose(fp)
    if (ferror(fp) != 0) {
        perror("ferror")
        exit(1)
    }
    return list
}


