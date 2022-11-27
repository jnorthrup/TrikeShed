package borg.trikeshed.common

import simple.PosixFile

/** lean on getline to read a file into a sequence of CharSeries */
actual fun readLinesSeq(path: String): Sequence<String>  = PosixFile.readLinesSeq(path)


/** lean on getline to read a file into a List of CharSeries */
actual fun readLines(path: String): List<String> = PosixFile.readLines(path)
