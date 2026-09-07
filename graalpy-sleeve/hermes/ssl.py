"""Parsimonious ssl waist — the real object graph, no real crypto.

Real TLS lives in JvmTlsCodecBackend (userspace.nio). This twin carries the exception
hierarchy, enums and constants that stdlib and asyncio bind at import time, so modules
that merely *mention* TLS load; every verb that would move bytes fails closed naming the seam.
"""
import socket as _socket

host = globals().get("host")

def _unavailable(op):
    raise SSLError(f"ssl.{op} is not available in the TrikeShed no-native guest — "
                   "route TLS through JvmTlsCodecBackend via host.call('tls_call', ...)")

# — exception hierarchy (asyncio.sslproto binds every one of these at import) —
class SSLError(OSError): pass
class SSLZeroReturnError(SSLError): pass
class SSLWantReadError(SSLError): pass
class SSLWantWriteError(SSLError): pass
class SSLSyscallError(SSLError): pass
class SSLEOFError(SSLError): pass
class SSLCertVerificationError(SSLError, ValueError):
    verify_code = 0
    verify_message = "certificate verification is not performed in the guest"
CertificateError = SSLCertVerificationError

# — protocol / verify / option constants —
PROTOCOL_TLS = 2
PROTOCOL_TLS_CLIENT = 16
PROTOCOL_TLS_SERVER = 17
PROTOCOL_TLSv1 = 3
PROTOCOL_TLSv1_1 = 4
PROTOCOL_TLSv1_2 = 5
CERT_NONE = 0
CERT_OPTIONAL = 1
CERT_REQUIRED = 2
VERIFY_DEFAULT = 0
VERIFY_CRL_CHECK_LEAF = 4
OP_ALL = 0x80000054
OP_NO_SSLv2 = 0x01000000
OP_NO_SSLv3 = 0x02000000
OP_NO_TLSv1 = 0x04000000
OP_NO_TLSv1_1 = 0x10000000
OP_NO_COMPRESSION = 0x00020000
OP_NO_TICKET = 0x00004000
HAS_SNI = True
HAS_ECDH = False
HAS_NPN = False
HAS_ALPN = True
HAS_TLSv1_3 = False
CHANNEL_BINDING_TYPES = []
OPENSSL_VERSION = "TrikeShed guest waist (no OpenSSL)"
OPENSSL_VERSION_NUMBER = 0
OPENSSL_VERSION_INFO = (0, 0, 0, 0, 0)

class TLSVersion:
    MINIMUM_SUPPORTED = -2
    SSLv3 = 768
    TLSv1 = 769
    TLSv1_1 = 770
    TLSv1_2 = 771
    TLSv1_3 = 772
    MAXIMUM_SUPPORTED = -1

class Purpose:
    SERVER_AUTH = "SERVER_AUTH"
    CLIENT_AUTH = "CLIENT_AUTH"

class SSLObject:
    """A TLS state machine with no state machine — every crossing fails closed."""
    def __init__(self, *a, **kw): _unavailable("SSLObject")

class SSLSocket(_socket.socket):
    def __init__(self, *a, **kw): _unavailable("SSLSocket")

class MemoryBIO:
    def __init__(self): _unavailable("MemoryBIO")

class SSLSession:
    def __init__(self, *a, **kw): _unavailable("SSLSession")

class SSLContext:
    """Configuration is real and inspectable; anything that would speak TLS is withheld."""
    sslsocket_class = SSLSocket
    sslobject_class = SSLObject

    def __init__(self, protocol=PROTOCOL_TLS):
        self.protocol = protocol
        self.check_hostname = False
        self.verify_mode = CERT_NONE
        self.options = OP_ALL
        self.minimum_version = TLSVersion.MINIMUM_SUPPORTED
        self.maximum_version = TLSVersion.MAXIMUM_SUPPORTED
        self.verify_flags = VERIFY_DEFAULT
        self.post_handshake_auth = False
        self.keylog_filename = None
        self.hostname_checks_common_name = True
        self.num_tickets = 2
        self.security_level = 0
        self._alpn = []
    def load_verify_locations(self, cafile=None, capath=None, cadata=None): pass
    def load_default_certs(self, purpose=Purpose.SERVER_AUTH): pass
    def load_cert_chain(self, certfile, keyfile=None, password=None): _unavailable("load_cert_chain")
    def set_ciphers(self, ciphers): pass
    def set_alpn_protocols(self, protocols): self._alpn = list(protocols)
    def set_npn_protocols(self, protocols): pass
    def set_default_verify_paths(self): pass
    def get_ca_certs(self, binary_form=False): return []
    def cert_store_stats(self): return {"x509": 0, "crl": 0, "x509_ca": 0}
    def wrap_socket(self, sock, *a, **kw): _unavailable("wrap_socket")
    def wrap_bio(self, incoming, outgoing, *a, **kw): _unavailable("wrap_bio")

def create_default_context(purpose=Purpose.SERVER_AUTH, *, cafile=None, capath=None, cadata=None):
    ctx = SSLContext(PROTOCOL_TLS_CLIENT)
    ctx.check_hostname = True
    ctx.verify_mode = CERT_REQUIRED
    return ctx

def _create_unverified_context(*a, **kw): return SSLContext(PROTOCOL_TLS_CLIENT)
_create_default_https_context = create_default_context
def wrap_socket(sock, *a, **kw): _unavailable("wrap_socket")
def get_default_verify_paths(): return (None, None, None, None, None, None)
def match_hostname(cert, hostname): _unavailable("match_hostname")
def cert_time_to_seconds(cert_time): _unavailable("cert_time_to_seconds")
def get_server_certificate(addr, *a, **kw): _unavailable("get_server_certificate")
def DER_cert_to_PEM_cert(der): _unavailable("DER_cert_to_PEM_cert")
def PEM_cert_to_DER_cert(pem): _unavailable("PEM_cert_to_DER_cert")
def RAND_bytes(n): _unavailable("RAND_bytes")
def RAND_status(): return False
