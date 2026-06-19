package borg.trikeshed.graal

import borg.trikeshed.parse.confix.ConfixDoc
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j

/**
 * BlackboardHarness — GraalPointcutHarness wrapped with ConfixBlackboard
 * 
 * This lives in jvmMain because GraalPointcutHarness is JVM-only.
 */
class BlackboardHarness(
    private val harness: GraalPointcutHarness,
    initialState: ConfixBlackboard = ConfixBlackboard.empty()
) {
    val blackboard = initialState
    
    /** Evaluate code in a language and update blackboard */
    fun eval(language: String, code: String): Any? {
        val result = harness.eval(language, code)
        // Auto-capture any blackboard updates from the script
        return result
    }
    
    /** Put a value into the blackboard from host */
    fun put(key: String, value: Any?): ConfixBlackboard =
        blackboard.put(key, value, "host")
    
    /** Get a value from the blackboard */
    fun get(key: String): Any? = blackboard.get(key)
    
    /** Subscribe to blackboard changes */
    fun subscribe(handler: (ConfixDoc) -> Unit): () -> Unit =
        blackboard.subscribe(handler)
    
    /** Get the current blackboard state */
    fun state(): ConfixDoc = blackboard.state
    
    /** Close both harness and release resources */
    fun close() {
        harness.close()
    }
}

/**
 * Create a BlackboardHarness with default GraalPointcutHarness
 */
fun createBlackboardHarness(): BlackboardHarness =
    BlackboardHarness(GraalPointcutHarness())