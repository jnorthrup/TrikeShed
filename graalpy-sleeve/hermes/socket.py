"""Parsimonious GraalPy socket waist — importable in the no-native guest, fail-closed on use.

CPython's `socket` is a native extension (banlist: `socket|CPython native socket module|route networking through userspace.nio`).
This sleeve exists so `import socket` succeeds and the inventory marks `socket` READY (sleeved).
Real networking is intentionally not reimplemented here; callers that actually need bytes on the wire
must go through the TrikeShed CCEK host delegate `socket_call` → userspace.nio / JvmTlsCodecBackend.
Until that delegate is wired, every method raises with a diagnostic that names the replacement seam,
so `env` and other non-network code paths unblock while network code fails closed with a useful message.
"""

import errno as _errno

host = globals().get("host")

# — constants expected by stdlib and third-party code —
AF_UNSPEC = 0
AF_UNIX = 1
AF_INET = 2
AF_INET6 = 10
SOCK_STREAM = 1
SOCK_DGRAM = 2
SOCK_RAW = 3
IPPROTO_TCP = 6
IPPROTO_UDP = 17
SOL_SOCKET = 1
SOL_TCP = 6
IPPROTO_IP = 0
IPPROTO_IPV6 = 41
SO_REUSEADDR = 2
SO_KEEPALIVE = 9
SO_REUSEPORT = 15
SO_BROADCAST = 6
SO_ERROR = 4
SO_LINGER = 13
SO_RCVBUF = 8
SO_SNDBUF = 7
TCP_NODELAY = 1
IPV6_V6ONLY = 26
MSG_PEEK = 2
MSG_WAITALL = 256
SOCK_NONBLOCK = 2048
SOCK_CLOEXEC = 524288
SHUT_RDWR = 2
SHUT_RD = 0
SHUT_WR = 1
AI_PASSIVE = 1
AI_CANONNAME = 2
EAI_NONAME = -2
INADDR_ANY = 0
SOMAXCONN = 128
has_ipv6 = False

# CPython sentinel: `http.client`/`urllib.request`/`smtplib` bind it in class bodies at import.
_GLOBAL_DEFAULT_TIMEOUT = object()

def _unavailable(op="socket"):
    raise OSError(_errno.ENOSYS,
        f"socket.{op} is not available in the TrikeShed no-native guest — "
        "route networking through userspace.nio (HtxElement → JvmTlsCodecBackend) "
        "via host.call('socket_call', ...) or use the host HTTP seam")

class socket:
    def __init__(self, family=AF_INET, type=SOCK_STREAM, proto=0, fileno=None):
        self.family = family
        self.type = type
        self.proto = proto
        self._closed = False
        # importing must succeed; construction is cheap — actual IO fails closed
    def connect(self, address): _unavailable("connect")
    def connect_ex(self, address): _unavailable("connect_ex")
    def bind(self, address): _unavailable("bind")
    def listen(self, backlog=5): _unavailable("listen")
    def accept(self): _unavailable("accept")
    def send(self, data, flags=0): _unavailable("send")
    def sendall(self, data, flags=0): _unavailable("sendall")
    def recv(self, bufsize, flags=0): _unavailable("recv")
    def recvfrom(self, bufsize, flags=0): _unavailable("recvfrom")
    def settimeout(self, v): pass
    def gettimeout(self): return None
    def setblocking(self, v): pass
    def setsockopt(self, *a, **kw): pass
    def getsockopt(self, *a, **kw): return 0
    def fileno(self): _unavailable("fileno")
    def close(self): self._closed = True
    def shutdown(self, how): _unavailable("shutdown")
    def __enter__(self): return self
    def __exit__(self, *a): self.close()
    def __repr__(self): return f"<TrikeShedSocket family={self.family} type={self.type} closed={self._closed}>"

def create_connection(address, timeout=None, source_address=None, socket_options=None):
    _unavailable("create_connection")

def gethostname(): 
    if host is not None:
        try:
            v = host.call("socket_gethostname")
            if v is not None:
                return str(v)
        except Exception:
            pass
    return "trikeshed-guest"

def gethostbyname(hostname): _unavailable("gethostbyname")
def getaddrinfo(host, port, family=0, type=0, proto=0, flags=0): _unavailable("getaddrinfo")

# re-export errno for callers that do `socket.error`
error = OSError
timeout = TimeoutError
herror = OSError
gaierror = OSError

# — byte-order helpers are pure arithmetic; they carry no capability, so they are ported —
import sys as _sys
_BIG_ENDIAN = _sys.byteorder == "big"

def htons(x): return int(x) & 0xFFFF if _BIG_ENDIAN else ((int(x) & 0xFF) << 8) | ((int(x) >> 8) & 0xFF)
def ntohs(x): return htons(x)
def htonl(x):
    v = int(x) & 0xFFFFFFFF
    return v if _BIG_ENDIAN else int.from_bytes(v.to_bytes(4, "little"), "big")
def ntohl(x): return htonl(x)

def getfqdn(name=""): return name or gethostname()
def getdefaulttimeout(): return _default_timeout
def setdefaulttimeout(timeout):
    global _default_timeout
    _default_timeout = timeout
_default_timeout = None

# — every remaining verb reaches the wire, so every remaining verb fails closed —
def socketpair(family=AF_UNIX, type=SOCK_STREAM, proto=0): _unavailable("socketpair")
def create_server(address, *, family=AF_INET, backlog=None, reuse_port=False, dualstack_ipv6=False):
    _unavailable("create_server")
def has_dualstack_ipv6(): return False
def inet_aton(ip_string): _unavailable("inet_aton")
def inet_ntoa(packed_ip): _unavailable("inet_ntoa")
def inet_pton(address_family, ip_string): _unavailable("inet_pton")
def inet_ntop(address_family, packed_ip): _unavailable("inet_ntop")
def getnameinfo(sockaddr, flags): _unavailable("getnameinfo")
def getservbyname(servicename, protocolname=None): _unavailable("getservbyname")
def getservbyport(port, protocolname=None): _unavailable("getservbyport")
def if_nametoindex(name): _unavailable("if_nametoindex")
def if_indextoname(index): _unavailable("if_indextoname")
