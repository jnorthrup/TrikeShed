package borg.trikeshed.graal.subvm

import borg.trikeshed.hyperspace.HyperspaceRoutine
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import keymux.KeyMux
import kotlinx.coroutines.coroutineScope

/**
 * HyperspaceLauncher — sources **active models directly from KeyMux**, no synthetic fallback.
 *
 * Active = `KeyMux.get("llm.<model>.key")` or `llm.default.key` or `llm.<vendor>.key` resolves
 * to a non-null raw key (ghosted view is never used for resolution). The launcher never
 * invents a model; if KeyMux has no key, that model is not launched.
 *
 * Common candidate set is derived from housed models + vendor prefixes. The launcher
 * probes each candidate via KeyMux, and for every hit it membranes a real
 * [JvmHyperspaceRoutine] (routine lives inside the subVM, raw-key stays on host,
 * transport is real Htx — no fake model code).
 */
object HyperspaceLauncher {

    private val candidateModels: List<String> = listOf(
        "openai/gpt-4o",
        "openai/gpt-4o-mini",
        "openai/gpt-5.6-luna",
        "openai/gpt-5.6-sol",
        "anthropic/claude-sonnet-4.5",
        "anthropic/claude-opus-4.5",
        "google/gemini-3.7-flash",
        "google/gemini-2.5-pro",
        "x-ai/grok-4.6",
        "deepseek/deepseek-v4-pro",
        "moonshotai/kimi-k3",
        "qwen/qwen3.8-max",
        "autonull/bio-plausible",
        "autonull/null-1",
        "bio-plausible/genome-7b",
        "bio-plausible/autonull-v1",
    )

    suspend fun activeModelIds(keyMux: KeyMux): List<String> {
        val active = mutableListOf<String>()
        for (mid in candidateModels) {
            val raw = keyMux.get("llm.${mid}.key")
                ?: keyMux.get("llm.${mid.substringBefore("/")}.key")
                ?: keyMux.get("llm.default.key")
            if (raw != null) active.add(mid)
        }
        try {
            val listed = keyMux.list("llm.")
            for (i in 0 until listed.size) {
                val k = listed[i].a
                if (k.endsWith(".key")) {
                    val mid = k.removePrefix("llm.").removeSuffix(".key")
                    if (mid !in active && mid != "default") active.add(mid)
                }
            }
        } catch (_: Throwable) { }
        return active
    }

    suspend fun launchAll(keyMux: KeyMux): List<HyperspaceRoutine> = coroutineScope {
        val active = activeModelIds(keyMux)
        active.map { mid -> JvmHyperspaceRoutine(modelId = mid, keyMux = keyMux) }
    }

    suspend fun launchOne(keyMux: KeyMux, modelId: String): HyperspaceRoutine {
        val active = activeModelIds(keyMux)
        require(modelId in active) { "Model $modelId is not active in KeyMux — no raw key for llm.${modelId}.key and no llm.default.key" }
        return JvmHyperspaceRoutine(modelId = modelId, keyMux = keyMux)
    }
}
