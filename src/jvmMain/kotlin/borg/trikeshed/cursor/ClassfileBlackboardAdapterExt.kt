package borg.trikeshed.cursor

import borg.trikeshed.parse.confix.confixDoc
import borg.trikeshed.parse.confix.Syntax
import borg.trikeshed.parse.confix.BlackBoardEntry

fun attachClassToBlackboard(blackboard: ConfixBlackboard, id: String, jsonStr: String) {
    val doc = confixDoc(jsonStr.encodeToByteArray(), Syntax.JSON)
    blackboard.put(id, BlackBoardEntry(doc))
}
