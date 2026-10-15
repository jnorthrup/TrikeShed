package borg.trikeshed.graal.subvm

/** Python's blocking TLS client surface over the socket lease, not native OpenSSL or an independent client. */
object PythonTlsProvider {
    const val MODULE = "ssl"

    val source = """
        import socket as _socket
        from enum import IntEnum, Enum

        class _SSLMethod(IntEnum):
            PROTOCOL_TLS_CLIENT = 16
            PROTOCOL_TLS_SERVER = 17
        PROTOCOL_TLS_CLIENT, PROTOCOL_TLS_SERVER = _SSLMethod
        class VerifyMode(IntEnum):
            CERT_NONE = 0
            CERT_OPTIONAL = 1
            CERT_REQUIRED = 2
        CERT_NONE, CERT_OPTIONAL, CERT_REQUIRED = VerifyMode
        class TLSVersion(IntEnum):
            TLSv1_2 = 0x303
            TLSv1_3 = 0x304
        class Purpose(Enum):
            SERVER_AUTH = '1.3.6.1.5.5.7.3.1'
            CLIENT_AUTH = '1.3.6.1.5.5.7.3.2'
        class SSLError(OSError): pass
        # Standard exception taxonomy is also imported by consumers which never
        # request nonblocking TLS. These names do not grant a BIO/native socket.
        class SSLZeroReturnError(SSLError): pass
        class SSLWantReadError(SSLError): pass
        class SSLWantWriteError(SSLError): pass
        class SSLSyscallError(SSLError): pass
        class SSLEOFError(SSLError): pass
        class SSLCertVerificationError(SSLError): pass
        CertificateError = SSLCertVerificationError
        # Compatibility consumers inspect this backend label even without using TLS.
        # No OpenSSL version is claimed; OpenSSL-only capabilities remain absent.
        OPENSSL_VERSION = 'TrikeShed host TLS lease (OpenSSL unavailable)'
        HAS_SNI = True
        HAS_ALPN = True
        _VERSIONS = {'TLS12': TLSVersion.TLSv1_2, 'TLS13': TLSVersion.TLSv1_3}
        _VERSION_NAMES = {'TLS12': 'TLSv1.2', 'TLS13': 'TLSv1.3'}

        def _unsupported():
            raise OSError(95, 'capability is not part of the host TLS lease')

        def _tls_call(operation, *args):
            try:
                return operation(*args)
            except OSError as error:
                if error.errno != 71:
                    raise
                kind = SSLCertVerificationError if getattr(error, 'tls_error', None) == 'certificate' else SSLError
                raise kind(error.errno, error.strerror) from error

        class SSLContext:
            # Trust, versions, ciphers and ALPN are a host-owned profile. Reject changes,
            # rather than accepting settings which the transport would silently ignore.
            __slots__ = ('_policy',)
            def __init__(self, protocol=PROTOCOL_TLS_CLIENT):
                if protocol != PROTOCOL_TLS_CLIENT:
                    _unsupported()
                self._policy = _socket._call('tls-policy')
            @property
            def protocol(self): return PROTOCOL_TLS_CLIENT
            @property
            def check_hostname(self): return True
            @check_hostname.setter
            def check_hostname(self, value):
                if value is not True: raise ValueError('host TLS requires hostname verification')
            @property
            def verify_mode(self): return CERT_REQUIRED
            @verify_mode.setter
            def verify_mode(self, value):
                if value != CERT_REQUIRED: raise ValueError('host TLS requires certificate verification')
            @property
            def minimum_version(self): return min(_VERSIONS[name] for name in self._policy['protocols'])
            @minimum_version.setter
            def minimum_version(self, value):
                if value != self.minimum_version: _unsupported()
            @property
            def maximum_version(self): return max(_VERSIONS[name] for name in self._policy['protocols'])
            @maximum_version.setter
            def maximum_version(self, value):
                if value != self.maximum_version: _unsupported()
            @property
            def post_handshake_auth(self): return None
            def set_alpn_protocols(self, protocols):
                if list(protocols) != self._policy['alpn']: _unsupported()
            def load_default_certs(self, purpose=Purpose.SERVER_AUTH):
                if purpose is not Purpose.SERVER_AUTH: _unsupported()
                # The profile already owns peer trust. No guest filesystem/CA override.
            def load_verify_locations(self, cafile=None, capath=None, cadata=None): _unsupported()
            def load_cert_chain(self, *args, **kwargs): _unsupported()
            def set_ciphers(self, ciphers): _unsupported()
            def wrap_bio(self, *args, **kwargs): _unsupported()
            def wrap_socket(self, sock, server_side=False, do_handshake_on_connect=True,
                            suppress_ragged_eofs=False, server_hostname=None, session=None):
                if server_side or not do_handshake_on_connect or suppress_ragged_eofs or session is not None:
                    _unsupported()
                if not isinstance(server_hostname, str) or not server_hostname:
                    raise ValueError('TLS requires a nonempty server_hostname')
                if not isinstance(sock, _socket.socket) or isinstance(sock, SSLSocket):
                    raise TypeError('TLS requires an owned TCP socket')
                if sock._closed or sock._files:
                    raise ValueError('TLS requires an open socket without file wrappers')
                result = _tls_call(sock._start_tls, server_hostname)
                if not result['verified']:
                    sock.close()
                    raise SSLCertVerificationError(71, 'TLS peer was not verified')
                wrapped = object.__new__(SSLSocket)
                wrapped.__dict__.update(sock.__dict__)
                wrapped._context, wrapped.server_hostname = self, server_hostname
                wrapped.server_side = False
                wrapped.do_handshake_on_connect = True
                wrapped.suppress_ragged_eofs = suppress_ragged_eofs
                wrapped._session = result
                # Exactly one owner: the previous socket can no longer read plaintext or
                # close the descriptor now held by the verified stream and its file views.
                sock._id, sock._closed = None, True
                return wrapped

        class SSLSocket(_socket.socket):
            def __init__(self, *args, **kwargs):
                raise TypeError('use SSLContext.wrap_socket')
            @property
            def context(self): return self._context
            def recv(self, bufsize=1024, flags=0):
                # The transport distinguishes close_notify from truncated ciphertext.
                # The lease refuses suppress_ragged_eofs rather than hiding truncation.
                return _tls_call(super().recv, bufsize, flags)
            def send(self, data, flags=0): return _tls_call(super().send, data, flags)
            def read(self, len=1024, buffer=None):
                return self.recv(len) if buffer is None else self.recv_into(buffer, len)
            def write(self, data): return self.send(data)
            def do_handshake(self, block=False): self._require()
            def version(self): return _VERSION_NAMES[self._session['protocol']] if self._id is not None else None
            def selected_alpn_protocol(self): return self._session['alpn'] if self._id is not None else None
            def getpeercert(self, binary_form=False): _unsupported()
            def get_channel_binding(self, cb_type='tls-unique'): _unsupported()
            def unwrap(self): _unsupported()

        def create_default_context(purpose=Purpose.SERVER_AUTH, *, cafile=None, capath=None, cadata=None):
            if purpose is not Purpose.SERVER_AUTH or any(value is not None for value in (cafile, capath, cadata)):
                _unsupported()
            return SSLContext(PROTOCOL_TLS_CLIENT)

        _create_default_https_context = create_default_context
        def _create_https_context(http_version):
            context = create_default_context()
            if http_version == 11: context.set_alpn_protocols(['http/1.1'])
            return context
    """.trimIndent()
}
