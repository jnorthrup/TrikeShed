import pytest
from tspy import host_bootstrap
import tspy

class MockEmitterProxy:
    def __init__(self):
        self.emitted_events = []

    def emit(self, event):
        self.emitted_events.append(event)

def test_install_pointcut_hooks_called(monkeypatch):
    install_called_with = None
    
    def mock_install_pointcut_hooks(port):
        nonlocal install_called_with
        install_called_with = port

    monkeypatch.setattr(tspy, "install_pointcut_hooks", mock_install_pointcut_hooks)
    
    proxy = MockEmitterProxy()
    host_bootstrap.install(proxy)
    
    assert install_called_with is not None
    assert isinstance(install_called_with, host_bootstrap.PortShim)
    assert install_called_with.emitter_proxy is proxy

    # Test that the shim forwards emit
    install_called_with.emit("test_event")
    assert proxy.emitted_events == ["test_event"]
