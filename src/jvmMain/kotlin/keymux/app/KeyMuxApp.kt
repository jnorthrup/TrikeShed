package keymux.app

import keymux.*
import modelmux.*
import modelmux.acp.*
import java.nio.file.Paths
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.*
import borg.trikeshed.lib.*
//temporary demo exception to the uring userspace nio without trekking far off the path 

fun main() = runBlocking {
    // ── 1. Build keymux: env > persist > api precedence ──
    val mux = KeyMux {
        env("APP")                                    // APP_LLMS__OPENAI__KEY etc.
        persist(Paths.get(System.getProperty("user.home"), ".config", "llm"))
        api("https://config.internal.example.com/v1")
    }

    // ── 2. Seed config (would normally come from env/persist) ──
    mux.set("llm.gpt-4.key",    System.getenv("OPENAI_API_KEY") ?: "sk-test")
    mux.set("llm.gpt-4.base_url", "https://api.openai.com/v1")
    mux.set("llm.claude-3.key", System.getenv("ANTHROPIC_API_KEY") ?: "sk-ant-test")
    mux.set("llm.claude-3.base_url", "https://api.anthropic.com/v1")
    mux.set("llm.embed-3.key",  System.getenv("OPENAI_API_KEY") ?: "sk-test")
    mux.set("llm.embed-3.base_url", "https://api.openai.com/v1")

    // ── 3. Build modelmux on top of keymux ──
    val models = ModelMux(mux) {
        model("gpt-4",     caps = _s["chat", "stream", "tools", "vision"])
        model("claude-3",  caps = _s["chat", "stream", "tools"])
        model("embed-3",   caps = _s["embed"])
    }

    // ── 4. Route by capability ──
    val route = models.route("chat", "tools", "vision")
    println("Routed models with chat+tools+vision: ${
        (0 until route.a.size).joinToString { route.a[it].a }}")

    // ── 5. Non-streaming chat ──
    val messages: Series<AcpMessage> = _l[
        "system" j "You are a helpful assistant.",
        "user"   j "Explain the Join algebra in one sentence."
    ].toSeries()

    // val response = models.chat("gpt-4", messages)
    // println("Response: ${response.a}")
    // println("Usage: prompt=${response.b.a} completion=${response.b.b}")

    // ── 6. Streaming chat ──
    // models.stream("gpt-4", messages).collect { chunk ->
    //     print(chunk.a)  // print delta text as it arrives
    // }

    // ── 7. Embeddings ──
    // val texts = _l["hello world", "join algebra"].toSeries()
    // val embeddings = models.embed("embed-3", texts)
    // for (i in 0 until embeddings.size) {
    //     val (text, vec) = embeddings[i]
    //     println("$text → ${vec.size} dimensions")
    // }

    // ── 8. Watch for config changes (NIO WatchService) ──
    // mux.watch("llm.").collect { (key, event) ->
    //     println("Config changed: $key ($event)")
    //     // Could trigger model hot-reload here
    // }

    // ── 9. Capability projection ──
    val chatModels = models.listModels("chat")
    println("Chat-capable models: ${
        (0 until chatModels.size).joinToString { chatModels[it].id }}")

    // ── 10. Full algebraic pipeline demonstration ──
    //   KeyPath → KeyMux.resolve → AcpMeta → AcpRequest → NioHttp → AcpResponse
    //   Each step is a pure Join composition; NIO is the effect boundary.
    println("keymux + modelmux wired. Key resolution pipeline:")
    val result = mux.getWithSource("llm.gpt-4.key")
    println("  llm.gpt-4.key → value=${result.a?.take(8)}... from=${result.b}")
}
