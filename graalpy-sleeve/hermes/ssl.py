"""Parsimonious ssl waist — lets `import ssl` succeed in the guest.

Real TLS lives in JvmTlsCodecBackend; this stub only unblocks imports.
Any actual wrap/connect raises with the seam name so network code fails closed verbally.
"""
import socket as _socket

host = globals().get("host")

PROTOCOL_TLS = 2
PROTOCOL_TLS_CLIENT = 16
CERT_NONE = 0
CERT_REQUIRED = 2

class SSLContext:
    def __init__(self, protocol=PROTOCOL_TLS):
        self.protocol = protocol
        self.check_hostname = False
        self.verify_mode = CERT_NONE
    def wrap_socket(self, sock, *a, **kw):
        raise OSError("ssl.wrap_socket unavailable in guest — use JvmTlsCodecBackend via host delegate")
    def load_verify_locations(self, *a, **kw): pass

def create_default_context(*a, **kw): return SSLContext()
def wrap_socket(sock, *a, **kw):
    raise OSError("ssl.wrap_socket unavailable in guest — use JvmTlsCodecBackend via host delegate")

SSLSocket = _socket.socket
SSLError = OSError
