package borg.trikeshed.graal.subvm

import borg.trikeshed.lib.*
import borg.trikeshed.reactor.*
import borg.trikeshed.userspace.nio.channels.*
import borg.trikeshed.userspace.nio.channels.spi.ChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.EgressAllowlist
import borg.trikeshed.userspace.nio.channels.spi.JvmChannelOperations
import borg.trikeshed.vm.Teleported
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import borg.trikeshed.agent.modelmux.Airgap

/** A VM-local socket ABI. Resolution checks existing policy; guest input never adds an egress permit. */
class PythonSocketProvider(
    scope: CoroutineScope,
    private val tlsConfig: TlsConfig = TlsConfig(alpnProtocols = s_[TlsApplicationProtocol.HTTP_1_1]),
    private val tlsBackend: TlsCodecBackend = scope.coroutineContext[TlsCodecBackend] ?: JvmTlsCodecBackend(),
) : AutoCloseable {
    private val admission = scope.coroutineContext[Airgap] ?: Airgap(false)
    private val sockets = PythonSocketElement(CoroutineScope(scope.coroutineContext + Dispatchers.IO))
    private val operations = scope.coroutineContext[ChannelOperations] ?: JvmChannelOperations()
    private val resolved = mutableMapOf<String, MutableSet<String>>()

    private fun address(host: String): ByteArray {
        if (!EgressAllowlist.permits(host) && resolved[host].orEmpty().none(EgressAllowlist::permits))
            throw SecurityException("socket destination is not leased: $host")
        val bytes = operations.resolve(host) ?: throw PythonSocketElement.SocketError(2)
        resolved.getOrPut(inet_ntop(bytes)) { mutableSetOf() }.add(host)
        return bytes
    }

    fun call(args: List<Teleported>): Join<Boolean, Map<String, Any?>> = try {
        fun text(at: Int) = (args.getOrNull(at) as? Teleported.Str)?.v ?: error("text argument $at required")
        fun number(at: Int) = (args.getOrNull(at) as? Teleported.Num)?.v ?: error("integer argument $at required")
        fun int(at: Int): Int = number(at).let { require(it in Int.MIN_VALUE..Int.MAX_VALUE); it.toInt() }
        fun arity(size: Int) { require(args.size == size) { "invalid socket argument count" } }
        val value: Any? = runBlocking(admission) {
            val operation = text(0)
            when (operation) {
                "tls-policy", "name", "shutdown", "close" -> Unit
                else -> Airgap.requireRemote()
            }
            when (operation) {
                "tls-policy" -> {
                    arity(1)
                    mapOf("protocols" to tlsConfig.protocols.view.map { it.name },
                        "alpn" to tlsConfig.alpnProtocols.view.map { it.wireName })
                }
                "open" -> { arity(2); sockets.socket(int(1)) }
                "resolve" -> {
                    arity(2)
                    val bytes = address(text(1))
                    mapOf("host" to inet_ntop(bytes), "family" to if (bytes.size == 4) 2 else 10)
                }
                "connect" -> {
                    arity(5)
                    val port = int(3).also { require(it in 1..65535) }
                    val bytes = address(text(2))
                    sockets.connect(int(1), if (bytes.size == 4) sockaddrIpv4(bytes, port) else sockaddrIpv6(bytes, port), number(4))
                    null
                }
                "tls" -> {
                    arity(5)
                    val hostname = text(2)
                    if (!EgressAllowlist.permits(hostname)) throw SecurityException("TLS peer is not leased: $hostname")
                    val session = sockets.tls(int(1), hostname, int(3), number(4), tlsConfig, tlsBackend)
                    mapOf("protocol" to session.protocol?.name, "cipher" to session.cipherSuite?.name,
                        "verified" to session.verified, "subject" to session.peerCertificate?.subject,
                        "alpn" to session.applicationProtocol?.wireName)
                }
                "read" -> { arity(4); sockets.read(int(1), int(2), number(3)).toHexString() }
                "write" -> {
                    arity(4)
                    val hex = text(2)
                    require(hex.length <= PythonSocketElement.MAX_BYTES * 2)
                    sockets.write(int(1), hex.hexToByteArray(), number(3))
                }
                "name" -> {
                    arity(3)
                    val bytes = sockets.name(int(1), int(2) != 0)
                    val family = sockaddrFamily(bytes)
                    mapOf("host" to inet_ntop(bytes.copyOfRange(if (family == 2) 4 else 8, if (family == 2) 8 else 24)),
                        "port" to (((bytes[2].toInt() and 255) shl 8) or (bytes[3].toInt() and 255)), "family" to family)
                }
                "shutdown" -> { arity(3); sockets.shutdown(int(1), int(2)); null }
                "close" -> { arity(2); sockets.close(int(1)); null }
                else -> throw PythonSocketElement.SocketError(95)
            }
        }
        true j mapOf("verdict" to "ok", "value" to value)
    } catch (failure: Exception) {
        val errno = when (failure) {
            is Airgap.Rejection -> 13
            is PythonSocketElement.SocketError -> failure.errno
            is SecurityException -> 13
            is javax.net.ssl.SSLException -> 71
            else -> 22
        }
        val certificateFailure = generateSequence<Throwable>(failure) { it.cause }.any { it is java.security.cert.CertificateException }
        false j mapOf("verdict" to "socket-error", "errno" to errno, "detail" to failure.message,
            "tlsError" to when {
                certificateFailure -> "certificate"
                failure is javax.net.ssl.SSLException -> "protocol"
                else -> null
            })
    }

    override fun close() = runBlocking { sockets.drain() }

    companion object {
        const val MODULE = "socket"
        /** TCP client operations only. Unsupported native capabilities are errors, not no-op substitutes. */
        val source = """
            import json, io, time, errno
            from enum import IntEnum
            from trikeshed_host import call as _host_call

            class AddressFamily(IntEnum):
                AF_UNSPEC = 0
                AF_INET = 2
                AF_INET6 = 10
            class SocketKind(IntEnum):
                SOCK_STREAM = 1
                SOCK_DGRAM = 2
            AF_UNSPEC, AF_INET, AF_INET6 = AddressFamily
            SOCK_STREAM, SOCK_DGRAM = SocketKind
            IPPROTO_IP, IPPROTO_TCP, IPPROTO_UDP = 0, 6, 17
            SHUT_RD, SHUT_WR, SHUT_RDWR = 0, 1, 2
            SOL_SOCKET, SO_REUSEADDR, SO_ERROR, SO_KEEPALIVE, TCP_NODELAY = 1, 2, 4, 9, 1
            AI_PASSIVE, AI_CANONNAME, AI_NUMERICHOST, AI_NUMERICSERV = 1, 2, 4, 1024
            has_ipv6 = True
            error = OSError
            timeout = TimeoutError
            class gaierror(OSError): pass
            MAX_BYTES, MAX_TIMEOUT = 65536, 30.0
            _default_timeout = MAX_TIMEOUT
            _GLOBAL_DEFAULT_TIMEOUT = object()

            def _call(operation, *args):
                reply = json.loads(_host_call('socket', operation, *args))
                if reply.get('verdict') != 'ok':
                    errno = reply.get('errno', 5)
                    kind = TimeoutError if errno == 110 else BlockingIOError if errno == 11 else OSError
                    failure = kind(errno, reply.get('detail', reply.get('verdict')))
                    failure.tls_error = reply.get('tlsError')
                    raise failure
                return reply['value']

            def _timeout(value):
                value = MAX_TIMEOUT if value is None else float(value)
                if not 0 <= value <= MAX_TIMEOUT: raise ValueError('socket lease timeout must be 0..30 seconds')
                return value
            def getdefaulttimeout(): return _default_timeout
            def setdefaulttimeout(value):
                global _default_timeout
                _default_timeout = _timeout(value)

            def getaddrinfo(host, port, family=AF_UNSPEC, type=0, proto=0, flags=0):
                if type not in (0, SOCK_STREAM) or proto not in (0, IPPROTO_TCP) or flags:
                    raise OSError(95, 'only TCP client address resolution is leased')
                address = _call('resolve', str(host))
                if family not in (AF_UNSPEC, address['family']): raise gaierror(-6, 'address family unavailable')
                endpoint = (address['host'], int(port))
                if address['family'] == AF_INET6: endpoint += (0, 0)
                return [(address['family'], SOCK_STREAM, IPPROTO_TCP, '', endpoint)]
            def gethostbyname(host): return getaddrinfo(host, 0, AF_INET)[0][4][0]

            class socket:
                def __init__(self, family=AF_INET, type=SOCK_STREAM, proto=0, fileno=None):
                    if type != SOCK_STREAM or proto not in (0, IPPROTO_TCP) or fileno is not None:
                        raise OSError(95, 'only new TCP client sockets are leased')
                    self.family, self.type, self.proto = AddressFamily(family), SocketKind(type), proto
                    self._id = _call('open', int(family))
                    self._timeout, self._closed, self._files = _default_timeout, False, 0
                def __enter__(self): return self
                def __exit__(self, *args): self.close()
                def _require(self):
                    if self._id is None: raise OSError(9, 'closed socket')
                    return self._id
                def settimeout(self, value): self._timeout = _timeout(value)
                def gettimeout(self): return self._timeout
                def setblocking(self, flag): self.settimeout(None if flag else 0)
                def getblocking(self): return self._timeout != 0
                def connect(self, address):
                    if self._timeout == 0: raise OSError(95, 'nonblocking connect is not leased')
                    if len(address) > 2 and any(address[2:]): raise OSError(95, 'IPv6 flow/scope options are not leased')
                    try: _call('connect', self._require(), str(address[0]), int(address[1]), max(1, int(self._timeout * 1000)))
                    except OSError:
                        self.close()
                        raise
                def connect_ex(self, address):
                    try: self.connect(address); return 0
                    except OSError as failure: return failure.errno
                def _start_tls(self, server_hostname):
                    # Explicit TrikeShed capability, not a pretend CPython ssl implementation.
                    if self._files or self._timeout == 0:
                        raise OSError(95, 'TLS upgrade requires a blocking socket without file wrappers')
                    try:
                        return _call('tls', self._require(), str(server_hostname), self.getpeername()[1], max(1, int(self._timeout * 1000)))
                    except OSError:
                        self.close()
                        raise
                def recv(self, bufsize, flags=0):
                    if flags: raise OSError(95, 'receive flags are not leased')
                    if bufsize < 0: raise ValueError('negative receive size')
                    return bytes.fromhex(_call('read', self._require(), min(int(bufsize), MAX_BYTES), int(self._timeout * 1000)))
                def recv_into(self, buffer, nbytes=0, flags=0):
                    view = memoryview(buffer).cast('B')
                    if view.readonly: raise TypeError('receive buffer must be writable')
                    size = len(view) if not nbytes else nbytes
                    if size > len(view): raise ValueError('buffer too small')
                    data = self.recv(size, flags)
                    view[:len(data)] = data
                    return len(data)
                def send(self, data, flags=0):
                    if flags: raise OSError(95, 'send flags are not leased')
                    data = memoryview(data).cast('B')[:MAX_BYTES].tobytes()
                    return _call('write', self._require(), data.hex(), int(self._timeout * 1000))
                def sendall(self, data, flags=0):
                    if flags: raise OSError(95, 'send flags are not leased')
                    view, sent = memoryview(data).cast('B'), 0
                    deadline = time.monotonic() + self._timeout
                    original = self._timeout
                    try:
                        while sent < len(view):
                            self._timeout = max(0, deadline - time.monotonic())
                            if original and not self._timeout: raise TimeoutError(110, 'sendall deadline')
                            count = self.send(view[sent:], flags)
                            if not count: raise OSError(32, 'socket made no write progress')
                            sent += count
                    finally: self._timeout = original
                def _name(self, peer):
                    value = _call('name', self._require(), int(peer))
                    endpoint = (value['host'], value['port'])
                    return endpoint if value['family'] == AF_INET else endpoint + (0, 0)
                def getsockname(self): return self._name(False)
                def getpeername(self): return self._name(True)
                def shutdown(self, how): _call('shutdown', self._require(), int(how))
                def close(self):
                    self._closed = True
                    if not self._files and self._id is not None:
                        _call('close', self._id)
                        self._id = None
                def fileno(self): raise OSError(95, 'host descriptors do not cross the VM boundary')
                def setsockopt(self, *args):
                    # stdlib HTTP treats this optional tuning's ENOPROTOOPT as a usable default.
                    if len(args) == 3 and args[:2] == (IPPROTO_TCP, TCP_NODELAY):
                        raise OSError(errno.ENOPROTOOPT, 'TCP_NODELAY is not leased')
                    raise OSError(95, 'socket options are not leased')
                def getsockopt(self, *args): raise OSError(95, 'socket options are not leased')
                def bind(self, *args): raise OSError(95, 'server sockets are not leased')
                def listen(self, *args): raise OSError(95, 'server sockets are not leased')
                def accept(self): raise OSError(95, 'server sockets are not leased')
                def makefile(self, mode='r', buffering=None, *, encoding=None, errors=None, newline=None):
                    if set(mode) - set('rwb'): raise ValueError('invalid mode')
                    reading, writing = 'r' in mode or 'w' not in mode, 'w' in mode
                    if buffering == 0 and 'b' not in mode: raise ValueError('unbuffered text I/O')
                    raw = SocketIO(self, reading, writing)
                    if buffering == 0: return raw
                    size = io.DEFAULT_BUFFER_SIZE if buffering is None or buffering <= 0 else buffering
                    if reading and writing: stream = io.BufferedRWPair(raw, raw, size)
                    elif reading: stream = io.BufferedReader(raw, size)
                    else: stream = io.BufferedWriter(raw, size)
                    return stream if 'b' in mode else io.TextIOWrapper(stream, encoding, errors, newline)

            class SocketIO(io.RawIOBase):
                def __init__(self, sock, reading, writing):
                    super().__init__()
                    self._sock, self._reading, self._writing = sock, reading, writing
                    sock._files += 1
                def readable(self): return self._reading
                def writable(self): return self._writing
                def readinto(self, buffer):
                    self._checkClosed()
                    if not self._reading: raise io.UnsupportedOperation('not readable')
                    try: return self._sock.recv_into(buffer)
                    except BlockingIOError: return None
                def write(self, data):
                    self._checkClosed()
                    if not self._writing: raise io.UnsupportedOperation('not writable')
                    try: return self._sock.send(data)
                    except BlockingIOError: return None
                def close(self):
                    if not self.closed:
                        try: super().close()
                        finally:
                            self._sock._files -= 1
                            if self._sock._closed: self._sock.close()

            def create_connection(address, timeout=_GLOBAL_DEFAULT_TIMEOUT, source_address=None, *, all_errors=False):
                if source_address is not None: raise OSError(95, 'source binding is not leased')
                failures = []
                for family, kind, proto, _, endpoint in getaddrinfo(address[0], address[1], type=SOCK_STREAM):
                    connection = socket(family, kind, proto)
                    try:
                        if timeout is not _GLOBAL_DEFAULT_TIMEOUT: connection.settimeout(timeout)
                        connection.connect(endpoint)
                        return connection
                    except OSError as failure:
                        failures.append(failure)
                        connection.close()
                    except BaseException:
                        connection.close()
                        raise
                if all_errors: raise ExceptionGroup('connection failed', failures)
                raise failures[-1]
        """.trimIndent()
    }
}
