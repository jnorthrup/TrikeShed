package borg.trikeshed.util.oroboros

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_DOUBLE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * One recursive macOS FSEvents stream over [root], per-file events, delivered on a private serial
 * dispatch queue. The kernel reports changed paths; nothing is polled, walked or stat'd to find them.
 * [sink] receives the absolute path and the FSEvents item flags of each event.
 */
internal class FsEventStream(root: String, latencySeconds: Double, private val sink: (String, Int) -> Unit) : AutoCloseable {
    private val arena = Arena.ofShared()
    private val stream: MemorySegment

    init {
        val cfPath = cfString(root)
        val values = arena.allocate(ADDRESS).also { it.set(ADDRESS, 0, cfPath) }
        val paths = CF_ARRAY_CREATE.invokeExact(MemorySegment.NULL, values, 1L, TYPE_ARRAY_CALLBACKS) as MemorySegment
        val upcall = LINKER.upcallStub(
            MethodHandles.lookup().findVirtual(FsEventStream::class.java, "deliver",
                MethodType.methodType(Void.TYPE, MemorySegment::class.java, MemorySegment::class.java, Long::class.javaPrimitiveType,
                    MemorySegment::class.java, MemorySegment::class.java, MemorySegment::class.java)).bindTo(this),
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS), arena)
        stream = FS_CREATE.invokeExact(MemorySegment.NULL, upcall, MemorySegment.NULL, paths, SINCE_NOW, latencySeconds,
            FLAG_NO_DEFER or FLAG_WATCH_ROOT or FLAG_FILE_EVENTS) as MemorySegment
        CF_RELEASE.invokeExact(paths); CF_RELEASE.invokeExact(cfPath)
        check(stream != MemorySegment.NULL) { "FSEventStreamCreate refused $root" }
        val queue = DISPATCH_QUEUE_CREATE.invokeExact(arena.allocateFrom("trikeshed.fsevents"), MemorySegment.NULL) as MemorySegment
        FS_SET_QUEUE.invokeExact(stream, queue)
        check((FS_START.invokeExact(stream) as Byte).toInt() != 0) { "FSEventStreamStart refused $root" }
    }

    @Suppress("unused") // bound by the upcall stub
    private fun deliver(ref: MemorySegment, info: MemorySegment, count: Long, paths: MemorySegment, flags: MemorySegment, ids: MemorySegment) {
        val n = count.toInt()
        val p = paths.reinterpret(8L * n); val f = flags.reinterpret(4L * n)
        for (i in 0 until n) sink(p.getAtIndex(ADDRESS, i.toLong()).reinterpret(Long.MAX_VALUE).getString(0), f.getAtIndex(JAVA_INT, i.toLong()))
    }

    override fun close() {
        FS_STOP.invokeExact(stream); FS_INVALIDATE.invokeExact(stream); FS_RELEASE.invokeExact(stream)
        arena.close()
    }

    private fun cfString(s: String): MemorySegment =
        CF_STRING_CREATE.invokeExact(MemorySegment.NULL, arena.allocateFrom(s), UTF8) as MemorySegment

    companion object {
        const val MUST_SCAN_SUBDIRS = 0x1
        const val USER_DROPPED = 0x2
        const val KERNEL_DROPPED = 0x4
        const val ROOT_CHANGED = 0x20
        const val ITEM_CREATED = 0x100
        const val ITEM_REMOVED = 0x200
        const val ITEM_RENAMED = 0x800

        private const val FLAG_NO_DEFER = 0x2
        private const val FLAG_WATCH_ROOT = 0x4
        private const val FLAG_FILE_EVENTS = 0x10
        private const val SINCE_NOW = -1L
        private const val UTF8 = 0x08000100

        private val LINKER = Linker.nativeLinker()
        private val FRAMEWORKS: SymbolLookup by lazy {
            SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreServices.framework/CoreServices", Arena.global())
                .or(SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", Arena.global()))
                .or(LINKER.defaultLookup())
        }
        private fun fn(name: String, d: FunctionDescriptor) = LINKER.downcallHandle(FRAMEWORKS.find(name).orElseThrow(), d)

        private val CF_STRING_CREATE by lazy { fn("CFStringCreateWithCString", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT)) }
        private val CF_ARRAY_CREATE by lazy { fn("CFArrayCreate", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS)) }
        private val CF_RELEASE by lazy { fn("CFRelease", FunctionDescriptor.ofVoid(ADDRESS)) }
        private val TYPE_ARRAY_CALLBACKS by lazy { FRAMEWORKS.find("kCFTypeArrayCallBacks").orElseThrow() }
        private val FS_CREATE by lazy { fn("FSEventStreamCreate", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, JAVA_DOUBLE, JAVA_INT)) }
        private val FS_SET_QUEUE by lazy { fn("FSEventStreamSetDispatchQueue", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)) }
        private val FS_START by lazy { fn("FSEventStreamStart", FunctionDescriptor.of(JAVA_BYTE, ADDRESS)) }
        private val FS_STOP by lazy { fn("FSEventStreamStop", FunctionDescriptor.ofVoid(ADDRESS)) }
        private val FS_INVALIDATE by lazy { fn("FSEventStreamInvalidate", FunctionDescriptor.ofVoid(ADDRESS)) }
        private val FS_RELEASE by lazy { fn("FSEventStreamRelease", FunctionDescriptor.ofVoid(ADDRESS)) }
        private val DISPATCH_QUEUE_CREATE by lazy { fn("dispatch_queue_create", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS)) }

        val available: Boolean by lazy {
            System.getProperty("os.name").startsWith("Mac") && runCatching { FS_CREATE; DISPATCH_QUEUE_CREATE; TYPE_ARRAY_CALLBACKS }.isSuccess
        }
    }
}
