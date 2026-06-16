package borg.trikeshed.activejs

/** Stub implementation for WASM target - GraalVM not available. */
class GraalEcmaLauncherImpl : GraalEcmaLauncher {
    override fun initialize(): GraalEcmaContext {
        return GraalEcmaContextStub()
    }
    
    override fun shutdown() {}
}

class GraalEcmaContextStub : GraalEcmaContext {
    override val polyglotContext: Any = "WASM_STUB"
    
    override fun eval(script: String): Any = "WASM_STUB: $script"
    
    override fun getBinding(name: String): Any? = null
    
    override fun putBinding(name: String, value: Any) {}
    
    override fun installPointcutHooks(target: Any, eventHandler: (PointcutEvent) -> Unit) {}
    
    override fun close() {}
}