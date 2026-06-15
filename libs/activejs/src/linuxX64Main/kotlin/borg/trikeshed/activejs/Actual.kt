package borg.trikeshed.activejs

import kotlinx.coroutines.CoroutineContext

/** Stub implementation for Linux native target - GraalVM not available. */
actual class GraalEcmaLauncher {
    actual fun initialize(context: CoroutineContext = kotlinx.coroutines.currentCoroutineContext()): GraalEcmaContext {
        return GraalEcmaContextStub()
    }
    
    actual fun shutdown() {}
}

actual class GraalEcmaContextStub : GraalEcmaContext {
    actual override val polyglotContext: Any = "LINUX_STUB"
    
    actual override fun eval(script: String): Any = "LINUX_STUB: $script"
    
    actual override fun getBinding(name: String): Any? = null
    
    actual override fun putBinding(name: String, value: Any) {}
    
    actual override fun installPointcutHooks(target: Any, eventHandler: (PointcutEvent) -> Unit) {}
}