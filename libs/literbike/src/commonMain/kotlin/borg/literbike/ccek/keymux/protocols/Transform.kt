package borg.literbike.ccek.keymux.protocols

// Re-export protocol translation functions from the parent package
// These are identical to the top-level transform.rs functions
import borg.literbike.ccek.keymux.anthropicToOpenai
import borg.literbike.ccek.keymux.openaiToAnthropic
import kotlinx.serialization.json.JsonElement

/**
 * Protocol translation functions - aliases for protocol namespace
 */
val ProtocolTransform = ProtocolTransformFns

object ProtocolTransformFns {
    /** Anthropic Request -> OpenAI Request */
    fun anthropicToOpenai(body: JsonElement): JsonElement = anthropicToOpenai(body)

    /** OpenAI Response -> Anthropic Response */
    fun openaiToAnthropic(body: JsonElement): JsonElement = openaiToAnthropic(body)
}
