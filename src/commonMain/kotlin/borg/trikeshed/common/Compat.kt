package borg.trikeshed.common

import borg.trikeshed.Files as RootFiles
import borg.trikeshed.System as RootSystem
import borg.trikeshed.TypeEvidence as RootTypeEvidence
import borg.trikeshed.Usable as RootUsable
import borg.trikeshed.LongSeries
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.lib.Series
import borg.trikeshed.toLongSeries as rootToLongSeries
import borg.trikeshed.toRowVec as rootToRowVec
import borg.trikeshed.mkdir as rootMkdir
import borg.trikeshed.mktemp as rootMktemp
import borg.trikeshed.readLines as rootReadLines
import borg.trikeshed.readLinesSeq as rootReadLinesSeq
import borg.trikeshed.rm as rootRm
import borg.trikeshed.lib.Join
import kotlin.time.Clock

typealias TypeEvidence = RootTypeEvidence
typealias Usable = RootUsable

object Files {
    fun readAllLines(filename: String): List<String> = RootFiles.readAllLines(filename)
    fun readAllBytes(filename: String): ByteArray = RootFiles.readAllBytes(filename)
    fun readString(filename: String): String = RootFiles.readString(filename)
    fun write(filename: String, bytes: ByteArray) = RootFiles.write(filename, bytes)
    fun write(filename: String, lines: List<String>) = RootFiles.write(filename, lines)
    fun write(filename: String, string: String) = RootFiles.write(filename, string)
    fun cwd(): String = RootFiles.cwd()
    fun exists(filename: String): Boolean = RootFiles.exists(filename)
    fun streamLines(fileName: String, bufsize: Int): Sequence<Join<Long, ByteArray>> =
        RootFiles.streamLines(fileName, bufsize)
    fun iterateLines(fileName: String, bufsize: Int): Iterable<Join<Long, Series<Byte>>> =
        RootFiles.iterateLines(fileName, bufsize)
}

object System {
    fun getenv(name: String, defaultVal: String? = null): String? = RootSystem.getenv(name, defaultVal)
    val homedir: String get() = RootSystem.homedir
}

fun System.currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
fun System.getProperty(string: String, defVal: String? = null): String? = getenv(string, defVal)

fun mktemp(): String = rootMktemp()
fun rm(path: String): Boolean = rootRm(path)
fun mkdir(path: String): Boolean = rootMkdir(path)
fun readLinesSeq(path: String): Sequence<String> = rootReadLinesSeq(path)
fun readLines(path: String): List<String> = rootReadLines(path)

fun <A> Series<A>.toLongSeries(): LongSeries<A> = rootToLongSeries()
fun TypeEvidence.toRowVec(): RowVec = rootToRowVec()
