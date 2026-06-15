package borg.trikeshed.activejs

import kotlinx.coroutines.CoroutineContext

/** Stub implementation for JS target - GraalVM not available. */
actual class GraalEcmaLauncherImpl : GraalEcmaLauncher {
    override fun initialize(context: CoroutineContext = kotlinx.coroutines.currentCoroutineContext()): GraalEcmaContext {
        return GraalEcmaContextStub()
    }
    
    override fun shutdown() {}
}

actual class GraalEcmaContextStub : GraalEcmaContext {
    override val polyglotContext: Any = "JS_STUB"
    
    override fun eval(script: String): Any = "JS_STUB: $script"
    
    override fun getBinding(name: String): Any? = null
    
    override fun putBinding(name: String, value: Any) {}
    
    override fun installPointcutHooks(target: Any, eventHandler: (PointcutEvent) -> Unit) {}
}