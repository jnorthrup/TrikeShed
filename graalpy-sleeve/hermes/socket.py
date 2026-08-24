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
AF_INET = 2
AF_INET6 = 10
AF_UNIX = 1
SOCK_STREAM = 1
SOCK_DGRAM = 2
SOCK_RAW = 3
IPPROTO_TCP = 6
IPPROTO_UDP = 17
SOL_SOCKET = 1
SO_REUSEADDR = 2
SHUT_RDWR = 2

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
