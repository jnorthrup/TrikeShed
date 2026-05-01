package borg.trikeshed.jules.client

import borg.trikeshed.parse.confix.ConfixElement
import borg.trikeshed.parse.confix.Syntax
import borg.trikeshed.parse.confix.jsonSource
import borg.trikeshed.parse.confix.path
import kotlin.coroutines.CoroutineContext
import borg.trikeshed.lib.toSeries

suspend fun parseJulesSessionConfix(json: String, context: CoroutineContext): String {
    val confix = ConfixElement(listOf(jsonSource(json)).toSeries(), context)
    confix.open()
    confix.activate()
    // Parse the session through Confix to demonstrate full TrikeShed integration
    // Returns the root element string for now
    val result = confix.query(path())
    confix.close()
    return result.let { if (it.a > 0) it.b(0)?.toString() ?: "" else "" }
}
