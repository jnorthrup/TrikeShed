@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "RedundantVisibilityModifier", "unused")

package borg.trikeshed.userspace.nio.file

import borg.trikeshed.lib.CharSeries
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.combine
import borg.trikeshed.lib.get
import borg.trikeshed.lib.s
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import kotlin.coroutines.coroutineContext

/**
 * Userspace Path — [CharSeries]-backed, CharSequence-first.
 *
 * Mirrors `java.nio.file.Path` but backed by TrikeShed's [CharSeries] algebra.
 * No syscalls, no file handles, no java.nio dependency inside TrikeShed.
 *
 * ## CharSeries guarantees
 * [CharSeries] implements `CharSequence` and `Series<Char>` simultaneously.
 * CharSequence delegation: `length`, `charAt(Int)`, `subSequence(Int,Int)`
 * Series<Char> delegation: `size`, `get(Int)`, `b(Int)`, iterator
 *
 * ## NIO Path API
 * - isAbsolute, isRelative, isRoot
 * - fileName, fileStem, extension, parent
 * - resolve, resolveSibling, relativize
 * - normalize, startsWith, endsWith, split
 * - compareTo, equals, hashCode, toString
 *
 * ## Extensions (beyond NIO)
 * - uri  → "file:///home/user"  (full URI)
 * - url  → "file:/home/user"     (single-slash URL)
 * - btrfs → preserves /@subvol, /var/ prefix conventions
 *
 * ## Construction
 * ```
 * Path("/home/user".s)             // CharSeries via .s
 * Path(CharSeries("home/user"))    // explicit CharSeries
 * Path("home", "user", "file.bin") // join with /
 * "home/user".s.path              // extension property
 * ```
 */
public class Path private constructor(
    private val chars: CharSeries,
) : CharSequence by chars,
    Comparable<Path> {

    // ── Path segment algebra ────────────────────────────────────────────

    /** True if this path is absolute (starts with `/`). */
    public fun isAbsolute(): Boolean = length > 0 && this[0] == '/'

    /** True if this path is relative (not absolute). */
    public fun isRelative(): Boolean = !isAbsolute()

    /** True if this path is the root `/`. */
    public fun isRoot(): Boolean = length == 1 && this[0] == '/'

    /** Last path component (after final `/`), or empty if root. */
    public val fileName: Path
        get() {
            val last = indexOfLastSegment()
            return if (last < 0) this
            else if (last == 0 && length == 1) EMPTY
            else make(subSequence(last + 1, length) as Series<Char>)
        }

    /** File name without extension (before last `.` in fileName). */
    public val fileStem: Path
        get() {
            val name = fileName
            val dot = name.indexOfLastDot()
            return if (dot <= 0) name else make(name.subSequence(0, dot) as Series<Char>)
        }

    /** Extension of fileName (after last `.`), or empty. */
    public val extension: Path
        get() {
            val name = fileName
            val dot = name.indexOfLastDot()
            return if (dot < 0 || dot == name.length - 1) EMPTY
            else make(name.subSequence(dot + 1, name.length) as Series<Char>)
        }

    /** Parent path, or null if already at root. */
    public val parent: Path?
        get() {
            if (isRoot()) return null
            val last = indexOfLastSegment()
            return if (last <= 0) EMPTY else make(subSequence(0, last) as Series<Char>)
        }

    /** Resolve [child] relative to this path. */
    public fun resolve(child: Path): Path =
        if (child.isAbsolute()) child
        else if (length == 0) child
        else {
            val result: Series<Char> = combine(chars, CharSeries(child))
            make(result)
        }

    /** Resolve [child] CharSequence relative to this path. */
    public fun resolve(child: CharSequence): Path = resolve(Path(child))

    /** Resolve sibling [other] in place of this path's fileName. */
    public fun resolveSibling(other: Path): Path =
        (parent ?: EMPTY).resolve(other)

    /** Resolve sibling [other] CharSequence. */
    public fun resolveSibling(other: CharSequence): Path = resolveSibling(Path(other))

    /** Relativize [other] relative to this path. */
    public fun relativize(other: Path): Path {
        if (isAbsolute() != other.isAbsolute()) return other
        val thisSegs = split()
        val otherSegs = other.split()
        val common = minOf(thisSegs.count, otherSegs.count)
        var i = 0
        while (i < common && thisSegs.nth(i) == otherSegs.nth(i)) i++
        val upCount = thisSegs.count - i
        // Build relative path with explicit CharArray
        var resultLen = upCount * 3  // "../" = 3 chars each
        for (si in i until otherSegs.count) resultLen += 1 + otherSegs.nth(si).length  // "/" + segment
        if (resultLen == 0) return EMPTY
        val buf = CharArray(resultLen)
        var pos = 0
        for (u in 0 until upCount) { buf[pos] = '.'; buf[pos+1] = '.'; buf[pos+2] = '/'; pos += 3 }
        for (si in i until otherSegs.count) {
            buf[pos] = '/'; pos++
            val seg = otherSegs.nth(si)
            for (j in 0 until seg.length) { buf[pos] = seg[j]; pos++ }
        }
        return make(  (buf.cs)  [0.. pos])
    }

    /** True if this path starts with the given [other] path. */
    public fun startsWith(other: Path): Boolean =
        other.length <= length &&
        (0 until other.length).all { this[it] == other[it] } &&
        (other.length == length || this[other.length] == '/')

    /** True if this path starts with the given [other] CharSequence. */
    public fun startsWith(other: CharSequence): Boolean = startsWith(Path(other))

    /** True if this path ends with the given [other] path. */
    public fun endsWith(other: Path): Boolean {
        if (other.length > length) return false
        val offset = length - other.length
        return (0 until other.length).all { this[offset + it] == other[it] }
    }

    /** True if this path ends with the given [other] CharSequence. */
    public fun endsWith(other: CharSequence): Boolean = endsWith(Path(other))

    /** Normalize: remove `.`, resolve `..`, collapse multiple separators. */
    public fun normalize(): Path {
        if (length == 0) return this
        val segs = split()
        val out = mutableListOf<Path>()
        for (idx in 0 until segs.count) {
            val seg = segs.nth(idx)
            val len = seg.length
            when {
                len == 1 && seg[0] == '.' -> { /* skip */ }
                len == 2 && seg[0] == '.' && seg[1] == '.' -> {
                    if (out.isNotEmpty()) out.removeAt(out.lastIndex)
                }
                len > 0 -> out.add(seg)
            }
        }
        if (out.isEmpty()) return if (isAbsolute()) SEP else EMPTY
        // Compute total length and build CharArray
        var totalLen = 0
        for (p in out) totalLen += p.length
        totalLen += out.size - 1  // separators
        val buf = CharArray(totalLen + (if (isAbsolute()) 1 else 0))
        var pos = 0
        if (isAbsolute()) { buf[0] = '/'; pos = 1 }
        for (pIdx in out.indices) {
            if (pIdx > 0) { buf[pos] = '/'; pos++ }
            val p = out[pIdx]
            for (j in p.indices) { buf[pos] = p[j]; pos++ }
        }
        return make(buf.cs[ 0.. pos])
    }

    /** Split this path into its segment components. */
    public fun split(sep: Path = SEP): PathSegments {
        if (length == 0) return PathSegments.EMPTY
        val out = mutableListOf<Path>()
        var start = if (this[0] == '/') 1 else 0
        while (start < length) {
            var end = start
            while (end < length && this[end] != '/') end++
            out.add(make(subSequence(start, end) as Series<Char>))
            start = end + 1
        }
        return PathSegments(out)
    }

    // ── URI/URL/BTRFS ──────────────────────────────────────────────────

    /** Full URI: `file:///home/user/data.bin` (triple-slash). */
    public val uri: Series<Char>
        get() {
            if (length == 0) return "file:".s
            val encoded = encodeForUri()
            return if (isAbsolute()) combine("file://".s as Series<Char>, encoded) else combine("file:".s as Series<Char>, encoded)
        }

    /** URL-compatible: `file:/home/user/data.bin` (single slash after scheme). */
    public val url: Series<Char>
        get() {
            if (length == 0) return "file:".s
            val encoded = encodeForUri()
            return if (isAbsolute()) combine("file:/".s as Series<Char>, encoded) else combine("file:".s as Series<Char>, encoded)
        }

    /** Btrfs subvolume-aware: preserves `/@subvol` and `/var/` prefix conventions. */
    public val btrfs: Series<Char>
        get() = chars  // identity: already a CharSeries

    // ── Comparable<Path> ───────────────────────────────────────────────

    override fun compareTo(other: Path): Int {
        val minLen = minOf(length, other.length)
        var i = 0
        while (i < minLen) {
            val cmp = this[i].compareTo(other[i])
            if (cmp != 0) return cmp
            i++
        }
        return length.compareTo(other.length)
    }

    // ── Object ──────────────────────────────────────────────────────────

    override fun equals(other: Any?): Boolean =
        other is Path && length == other.length &&
        (0 until length).all { this[it] == other[it] }

    override fun hashCode(): Int {
        var h = 0
        for (i in 0 until length) h = 31 * h + this[i].hashCode()
        return h
    }

    // toString() — FFI boundary only, returns String for java.nio.file.Path interop
    override fun toString(): String {
        val sb = StringBuilder(length)
        for (i in 0 until length) sb.append(this[i])
        return sb.toString()
    }

    // ── Private helpers ────────────────────────────────────────────────

    /** Find the index of the last `/` in this path, or -1 if none. */
    private fun indexOfLastSegment(): Int {
        var i = length - 1
        while (i >= 0) {
            if (this[i] == '/') return i
            i--
        }
        return -1
    }

    /** Find the index of the last `.` in this path, or -1 if none. */
    private fun indexOfLastDot(): Int {
        var i = length - 1
        while (i >= 0) {
            if (this[i] == '.') return i
            i--
        }
        return -1
    }

    /** Percent-encode this path for URI use. Returns CharSeries. */
    private fun encodeForUri(): Series<Char> {
        if (length == 0) return chars
        val buf = StringBuilder(length * 3)
        var i = 0
        while (i < length) {
            val c = this[i]
            when (c) {
                '/', '.', '-', '_' -> buf.append(c)
                in 'a'..'z', in 'A'..'Z', in '0'..'9' -> buf.append(c)
                ' ' -> buf.append("%20")
                else -> {
                    buf.append('%')
                    buf.append(c.code.toString(16).uppercase().padStart(2, '0'))
                }
            }
            i++
        }
        return CharSeries(buf.toString())
    }

    // ── Constructors ───────────────────────────────────────────────────

    /** Primary: back a path by a [CharSequence] (converts to CharSeries). */
    public constructor(charSequence: CharSequence) : this(CharSeries(charSequence))

    /** Internal: accept any [Series]<[Char]> including [Join] from combine(). */
    internal constructor(series: Series<Char>) : this(CharSeries(series))

    // ── Companion: factory + constants ─────────────────────────────────

    /** Internal: make a Path from Series<Char>, wrapping Join results in CharSeries. */
    private fun make(series: Series<Char>): Path = Path(CharSeries(series))

    public companion object {
        private val SEP = Path("/".s)
        private val EMPTY = Path("".s)

        /** Platform path separator: `/` on Unix. */
        public val separator: Path get() = SEP

        /** Get the current working directory as a Path. */
        public suspend fun getDefault(): Path {
            val fo: FileOperations = coroutineContext[FileOperations.Key]
                ?: error("FileOperations not in coroutine context")
            return Path(fo.cwd())
        }

        /** Create a Path from [first] + [more] components, joined with `/`. */
        public fun of(first: CharSequence, vararg more: CharSequence): Path {
            if (more.isEmpty()) return Path(first)
            var result: Path = Path(first)
            for (m in more) result = result.resolve(Path(m))
            return result
        }

        /** Root path `/`. */
        public val root: Path get() = SEP

        /** Internal: concat two Series<Char> into a CharSeries. */
        private fun concat(a: Series<Char>, b: Series<Char>): Series<Char> {
            val result = combine(a, b)
            return CharSeries(result)
        }
    }
}

// ── PathSegments: IO-edge wrapper for List<Path> as Series<Path> ────────

/**
 * [Series]<[Path]> wrapper over a [List]<[Path]>.
 * Accepts List at the IO edge, exposes Series<Path> algebra to internal callers.
 */
public class PathSegments(segments: List<Path>) {
    private val list: List<Path> = segments

    /** Get the nth path segment. */
    public fun nth(n: Int): Path = list[n]

    /** Number of segments. */
    public val count: Int get() = list.size

    // Series<Path> algebra
    public val size: Int get() = list.size
    public fun get(index: Int): Path = list[index]
    public fun iterator(): Iterator<Path> = list.iterator()

    /** Empty PathSegments singleton. */
    public companion object {
        val EMPTY = PathSegments(emptyList())
    }
}

// ── Extension properties & functions ────────────────────────────────────

val CharArray.cs: CharSeries
    get() = CharSeries(this)
val Array<Char>.cs: CharSeries
    get() = CharSeries(this.toCharArray())

/** Extension property on CharSequence to create a userspace Path. */
public val CharSequence.path: Path get() = Path(this)

/** Create a Path from a String. (FFI boundary entry.) */
public fun CharSequence.toPath(): Path = Path(this)

/** Convert this Path to a filesystem-safe String. (FFI boundary exit.) */
public fun Path.toFileString(): CharSequence = this

/** Resolve a child string relative to this Path. */

/** Create a [Path] from [this] Series<Char>. */
public fun Series<Char>.toPath(): Path = Path(this)
