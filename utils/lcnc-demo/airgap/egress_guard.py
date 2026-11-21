#!/usr/bin/env python3
"""Egress ledger for one Python engine process.

    python egress_guard.py SCRIPT [ARGS...]      run SCRIPT as the main program with the guard installed

install() wraps this process's socket layer. Every attempt to connect, bind, send to an address or resolve a
name is recorded (operation, family, destination, the code that asked; never payload) and refused with
PermissionError. An attempt is `loopback` when its destination is a loopback address and `external`
otherwise (a name to resolve is external). Unix-domain sockets are local and pass. When the process ends it
appends one JSON line to the file named by TRIKESHED_EGRESS_LOG: pid, parent pid, script, whether the kernel
already denies network to this process, and what was attempted.

The ledger sees Python sockets only. Native code that opens a socket itself is outside it; the kernel
sandbox the host starts the process tree under is what denies that, and kernel_denied() reports whether
that denial is in force for this process.
"""
import atexit
import errno
import ipaddress
import json
import os
import socket
import sys
import time

LOG_ENV = 'TRIKESHED_EGRESS_LOG'
PROBE_ADDRESS = ('192.0.2.1', 9)        # TEST-NET-1 (RFC 5737): reserved for documentation, never routed
MAX_RECORDS = 50

_state = {'installed': False, 'attempts': 0, 'records': [], 'started': None, 'kernel': None, 'script': None}


def kernel_denied():
    """(denied, errno name). A datagram socket is pointed at a documentation address: connect() on a datagram
    socket sets its destination and sends nothing, so the probe is not itself traffic. EPERM or EACCES is a
    policy denial; any other outcome means the kernel would have let the socket be used."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    except OSError as e:
        return _denial(e)
    try:
        _REAL.get('connect', socket.socket.connect)(s, PROBE_ADDRESS)
        return False, None
    except OSError as e:
        return _denial(e)
    finally:
        s.close()


def _denial(e):
    code = e.errno or 0
    return code in (errno.EPERM, errno.EACCES), errno.errorcode.get(code, str(e.errno))


_REAL = {}
_HERE = os.path.abspath(__file__)


def _caller():
    """module path and function of the code that asked, e.g. urllib3/util/connection.py:_has_ipv6."""
    frame = sys._getframe(1)
    while frame is not None and os.path.abspath(frame.f_code.co_filename) == _HERE:
        frame = frame.f_back
    if frame is None:
        return None
    return '%s:%s' % ('/'.join(frame.f_code.co_filename.replace(os.sep, '/').split('/')[-3:]), frame.f_code.co_name)


def _loopback(to):
    host = to[0] if isinstance(to, tuple) and to else to
    try:
        return ipaddress.ip_address(host.decode() if isinstance(host, bytes) else host).is_loopback
    except (ValueError, AttributeError):
        return False


def _note(op, family, kind, to):
    _state['attempts'] += 1
    scope = 'loopback' if _loopback(to) else 'external'
    _state[scope] = _state.get(scope, 0) + 1
    if len(_state['records']) < MAX_RECORDS:
        _state['records'].append({'op': op, 'family': getattr(family, 'name', str(family)),
                                  'type': getattr(kind, 'name', str(kind)), 'to': repr(to), 'scope': scope,
                                  'by': _caller()})


def _numeric(host):
    if host is None or host == '':
        return True
    try:
        ipaddress.ip_address(host.decode() if isinstance(host, bytes) else host)
        return True
    except ValueError:
        return False


def install():
    if _state['installed']:
        return
    _state.update(installed=True, started=time.time(), script=sys.argv[0] if sys.argv else None)
    for name in ('connect', 'connect_ex', 'bind', 'sendto', 'sendmsg'):
        _REAL[name] = getattr(socket.socket, name)
    for name in ('getaddrinfo', 'gethostbyname', 'gethostbyname_ex', 'gethostbyaddr'):
        _REAL[name] = getattr(socket, name)
    _state['kernel'] = kernel_denied()

    def refuse(op):
        raise PermissionError(errno.EPERM, 'egress guard: %s refused' % op)

    def method(op):
        real = _REAL[op]

        def guarded(self, *args, **kwargs):
            if self.family == socket.AF_UNIX:
                return real(self, *args, **kwargs)
            if op == 'sendmsg' and len(args) < 4 and 'address' not in kwargs:
                return real(self, *args, **kwargs)          # no destination of its own
            to = kwargs.get('address', args[-1] if args else None) if op in ('sendto', 'sendmsg') else (args[0] if args else None)
            _note(op, self.family, self.type, to)
            if op == 'connect_ex':
                return errno.EPERM
            refuse(op)
        guarded.__name__ = op
        return guarded

    for name in ('connect', 'connect_ex', 'bind', 'sendto', 'sendmsg'):
        setattr(socket.socket, name, method(name))

    def resolver(op):
        real = _REAL[op]

        def guarded(host, *args, **kwargs):
            if _numeric(host):
                return real(host, *args, **kwargs)
            _note(op, 'name', 'resolve', host)
            refuse(op)
        guarded.__name__ = op
        return guarded

    for name in ('getaddrinfo', 'gethostbyname', 'gethostbyname_ex', 'gethostbyaddr'):
        setattr(socket, name, resolver(name))
    atexit.register(_report)


def snapshot():
    denied, code = _state['kernel'] if _state['kernel'] else (None, None)
    return {'guard': 'installed' if _state['installed'] else 'absent', 'pid': os.getpid(),
            'attempts': _state['attempts'], 'external': _state.get('external', 0), 'loopback': _state.get('loopback', 0),
            'kernelDenied': denied, 'kernelProbe': code}


def _report():
    path = os.environ.get(LOG_ENV)
    if not path:
        return
    line = json.dumps({**snapshot(), 'ppid': os.getppid(), 'script': _state['script'], 'executable': sys.executable,
                       'refused': _state['records'], 'started': _state['started'], 'ended': time.time()},
                      sort_keys=True) + '\n'
    fd = os.open(path, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o644)
    try:
        os.write(fd, line.encode('utf-8'))      # one write: lines from several processes do not interleave
    finally:
        os.close(fd)


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    import runpy
    sys.modules.setdefault('egress_guard', sys.modules[__name__])    # the program can ask for the ledger
    script = os.path.abspath(sys.argv[1])
    sys.argv = sys.argv[1:]
    sys.path[0] = os.path.dirname(script)                            # as if the script had been started directly
    install()
    _state['script'] = script
    runpy.run_path(script, run_name='__main__')


if __name__ == '__main__':
    main()
