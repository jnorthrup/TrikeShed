import tspy

class PortShim:
    def __init__(self, emitter_proxy):
        self.emitter_proxy = emitter_proxy

    def emit(self, event):
        self.emitter_proxy.emit(event)

def install(emitter_proxy):
    port = PortShim(emitter_proxy)
    # Assuming tspy has an install_pointcut_hooks method
    tspy.install_pointcut_hooks(port)
