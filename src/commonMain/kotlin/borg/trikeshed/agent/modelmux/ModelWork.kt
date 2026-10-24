package borg.trikeshed.agent.modelmux

import borg.trikeshed.parse.confix.ConfixDoc

/** Host-side inference work; never converts credentials into a guest environment. */
data class ModelWork(val model: String, val request: ConfixDoc)

/** The existing ModelMux owns KeyMux resolution, transport and response validation. */
suspend fun executeModelWork(mux: ModelMux, work: ModelWork): ConfixDoc =
    mux.completion(work.model, work.request).getOrThrow()
