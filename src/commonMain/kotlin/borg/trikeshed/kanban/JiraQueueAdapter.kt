package borg.trikeshed.kanban

class JiraQueueAdapter(val appendWal: AppendWal) {
    fun ingest(ticketId: String, summary: String, description: String) {
        val payload = "$summary\n$description".encodeToByteArray()
        appendWal.append(ticketId, payload)
    }

    fun sync(): Map<String, String> {
        val tickets = mutableMapOf<String, String>()
        appendWal.replay().forEach { (key, payload) ->
            tickets[key] = payload.decodeToString()
        }
        return tickets
    }
}
