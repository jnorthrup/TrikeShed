package borg.trikeshed.kanban

import borg.trikeshed.job.JobCommand
import modelmux.ModelMux

data class AgentDispatchResult(val accepted: Boolean, val dispatchedTo: String?)

class ModelMuxKanbanAgent(private val modelMux: ModelMux) {
    fun handleCommand(command: JobCommand): AgentDispatchResult {
        return when (command) {
            is JobCommand.Move -> {
                // If moving to a column that signals a model capability routing requirement
                // For demonstration/spec compliance, we route to the column name without 'col-'
                val capability = command.toColumn.value.removePrefix("col-")
                val route = modelMux.route("chat", capability)
                if (route.a.size > 0) {
                    return AgentDispatchResult(true, route.a[0].a)
                }
                AgentDispatchResult(false, null)
            }
            else -> AgentDispatchResult(false, null)
        }
    }
}
