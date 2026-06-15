package borg.trikeshed.activejs

import kotlinx.coroutines.CoroutineContext

/** Stub implementation for JS target - GraalVM not available. */
actual class GraalEcmaLauncher {
    actual fun initialize(context: CoroutineContext = kotlinx.coroutines.currentCoroutineContext()): GraalEcmaContext {
        return GraalEcmaContextStub()
    }
    
    actual fun shutdown() {}
}

actual class GraalEcmaContextStub : GraalEcmaContext {
    actual override val polyglotContext: Any = "JS_STUB"
    
    actual override fun eval(script: String): Any = "JS_STUB: $script"
    
    actual override fun getBinding(name: String): Any? = null
    
    actual override fun putBinding(name: String, value: Any) {}
    
    actual override fun installPointcutHooks(target: Any, eventHandler: (PointcutEvent) -> Unit) {}
}