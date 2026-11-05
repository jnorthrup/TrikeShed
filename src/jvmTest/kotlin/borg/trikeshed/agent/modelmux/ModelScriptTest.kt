package borg.trikeshed.agent.modelmux

import borg.trikeshed.agent.keymux.KeyMux
import borg.trikeshed.lib.*
import kotlinx.coroutines.*
import kotlin.test.*

class ModelScriptTest {
    val script = ModelScript.parse("""{"name":"bench-room","fallback":"(no cue)","cues":[
        {"match":"Lead counsel","replies":["first argument","second argument"]},
        {"match":"Paralegal","replies":["deadline is Friday 5pm"]}]}""")

    fun mux(): ModelMux = ModelMux(KeyMux { }) {
        model(id = "room", caps = setOf("chat"), baseUrl = ModelScript.register(script), provider = "script")
        model(id = "remote", caps = setOf("chat"), baseUrl = "https://example.invalid/v1", provider = "remote")
    }

    suspend fun ask(mux: ModelMux, model: String, system: String): Result<String> =
        mux.chat(model, s_["system" j system, "user" j "the Friday filing"]).map { it.a }

    @Test fun scriptedSeatsSpeakInTurnUnderAirgapWhileNetworkCardsStayRefused(): Unit = runBlocking(Airgap()) {
        val mux = mux()
        assertEquals("first argument", ask(mux, "room", "You are Lead counsel.").getOrThrow())
        assertEquals("deadline is Friday 5pm", ask(mux, "room", "You are the Paralegal.").getOrThrow())
        assertEquals("second argument", ask(mux, "room", "You are Lead counsel.").getOrThrow())
        assertEquals("second argument", ask(mux, "room", "You are Lead counsel.").getOrThrow())
        assertEquals("(no cue)", ask(mux, "room", "You are the judge.").getOrThrow())
        assertEquals(200, mux.lastReceipt?.httpStatus)
        assertIs<Airgap.Rejection>(ask(mux, "remote", "You are Lead counsel.").exceptionOrNull())
    }
}
