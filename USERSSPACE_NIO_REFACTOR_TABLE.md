# REFACTORING TABLE — userspace NIO → JVM NIO alignment
# ============================================================
#
# PRINCIPLE: 
#   - Userspace VFS = raw syscall seat (pread/pwrite/socket on posix, raw fd)
#   - JVM NIO Files/File = Java SE layer that sits atop userspace VFS via Selector/Channel
#   - commonMain defines the ALGEBRA; JVM actual does: typealias java.nio.* or trivial wrapper
#
# IntelliJ REFACTOR step-by-step:
#   1. Rename classes in commonMain expect/actual (Tier 1-3)
#   2. Update all imports across codebase
#   3. Create JVM actual typealiases to java.nio.* (Tier 5)
#   4. Verify compile on all targets
#
# ============================================================
# PART 1: CORE BUFFER/CHANNEL TYPES — align with java.nio naming
# ============================================================
#
# CURRENT (commonMain)              JAVA NIO EQUIVALENT           JVM ALIAS STRATEGY
# expect class UserspaceBuffer      java.nio.ByteBuffer        → typealias UserspaceBuffer = ByteBuffer
#   .size: Int                   .capacity(): Int         → .capacity
#   .get(Int): Byte              .get(Int): Byte           → (same)
#   .put(Int, Byte)              .put(Int, Byte)           → (same)
#
# expect class UserspaceFD           java.nio.channels.FileChannel → typealias UserspaceFD = Channel
#   .id: Int                   .validOps() or fd         → property extension .fd: Int
#   .isInvalid(): Boolean       !.isOpen                  → .isOpen
#
# NOT IN COMMON (delete): NioBuffer — fold entirely into UserspaceBuffer
#
# ============================================================
# PART 2: RING/SELECTOR — unify the submit/wait model
# ============================================================
#
# CURRENT (commonMain)              JAVA NIO EQUIVALENT           JVM ALIAS STRATEGY
# interface UserspaceRing           java.nio.channels.Selector  → typealias UserspaceRing = Selector
#  : CoroutineContext.Element
#   .prepRead(fd,b,off,ud)       —                        → .interestOps with OP_READ
#   .prepWrite(fd,b,off,ud)       —                        → .interestOps with OP_WRITE
#   .prepAccept(fd,ud)            —                        → .interestOps with OP_ACCEPT
#   .prepConnect(fd,a,p,ud)         —                        → .interestOps with OP_CONNECT
#   .prepClose(fd,ud)              —                        → implicit on .close()
#   .submit(): Int               .selectNow(): Int          → (same)
#   .wait(min): List<IOResult>  .select(timeout): Set   → .select; return .selectedKeys
#   .peek(): List<IOResult>         .selectedKeys()        → (keep as .selectedKeys clear)
#
# interface UserspaceSPI          java.nio.channels.spi.   → typealias UserspaceSPI = SelectorProvider
#                                SelectorProvider
#   .createRing(entries)         .openSelector()        → (same)
#   .openFile(path,readOnly)    .openFileChannel(path) → (same)
#   .createSocket(d,t,p)        .openSocketChannel() → (same)
#   .wrapBuffer(ByteArray)       ByteBuffer.wrap()     → (same)
#
# expect val userspaceSPI         —                        → expect val ioSpi
#
# ============================================================
# PART 3: BACKEND — Selector IS the PlatformBackend
# ============================================================
#
# CURRENT (commonMain)              JAVA NIO EQUIVALENT           JVM ALIAS STRATEGY
# interface NioObject             java.nio.channels.Channel   → merge/delete
#   .asRawFd(): Int?              ._ provider or stub   → DELETE
#   .isOpen(): Boolean            .isOpen               → (keep)
#
# interface PlatformBackend     java.nio.channels.Selector → DELETE (Selector IS backend)
#   .register(fd,token,interest)  .register(key,att)  → DELETE
#   .reregister                   —                    → DELETE
#   .unregister                  .cancel()             → DELETE
#   .submitRead/submitWrite        —                    → DELETE
#   .submit                      .selectNow()          → DELETE
#   .wait                        .select(timeout)       → DELETE
#   .pollCompletion              .selectedKeys iteration → DELETE
#
# NioSpiBackend (JVM)               —                    → DELETE use Selector directly
# UserspaceNioProvider (JVM)        —                    → DELETE use SelectorProvider directly
#
# ============================================================
# PART 4: SOCKET TYPES — use java.nio.channels names
# ============================================================
#
# CURRENT (commonMain)              JAVA NIO EQUIVALENT           JVM ALIAS STRATEGY
# sealed class SocketAddress      java.net.SocketAddress    → (keep, or typealias)
#   Inet(host,port)             InetSocketAddress       → (keep)
#   Unix(path)                   UnixDomainSocketAddress  → (keep)
#
# interface ConnectedSocket       java.nio.channels.SocketChannel → typealias ConnectedSocket = SocketChannel
#   .remoteAddress              .getRemoteAddress()        → (keep)
#   .read(buf,off,len)          .read(ByteBuffer)         → (keep)
#   .write(buf,off,len)          .write(ByteBuffer)        → (keep)
#   .close()                    .close()              → (keep)
#
# interface ListeningSocket       java.nio.channels.ServerSocketChannel → typealias ServerSocket = ServerSocketChannel
#   .bindAddress                .getLocalAddress()      → (keep)
#   .accept()                   .accept()            → (keep)
#   .close()                   .close()              → (keep)
#
# SocketPlatformExpect.kt         (test only)           → DELETE use channel actuals
#
# ============================================================
# PART 5: SPI LAYER — fold into SelectorProvider
# ============================================================
#
# NOT IN COMMON (delete): UserspaceNioSpi, NioUserspaceElement
#   - Already duplicated: UserspaceSPI handles factory
#   - SelectorProvider IS the SPI
#   - UserspaceRing IS the Selector
# ============================================================
#
# JVM ACTUAL TYPEALIAS PATTERN:
# ============================================================
# src/jvmMain/kotlin/borg/trikeshed/userspace/UserspaceIO.kt
#
# typealias UserspaceBuffer = java.nio.ByteBuffer
# typealias UserspaceFD = java.nio.channels.FileChannel
# typealias UserspaceRing = java.nio.channels.Selector
# typealias UserspaceSPI = java.nio.channels.spi.SelectorProvider
# typealias ConnectedSocket = java.nio.channels.SocketChannel
# typealias ServerSocket = java.nio.channels.ServerSocketChannel
#
# JVM actual for Socket Types:
# actual fun createConnectedSocket(host: String, port: Int): ConnectedSocket =
#     SocketChannel.open(InetSocketAddress(host, port))
# actual fun createListeningSocket(host: String, port: Int): ListeningSocket =
#     ServerSocketChannel.open().also { it.bind(InetSocketAddress(host, port)) }