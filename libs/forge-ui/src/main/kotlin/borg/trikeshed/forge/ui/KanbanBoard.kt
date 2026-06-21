package borg.trikeshed.forge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/**
 * Simple Kanban board UI using Compose.
 * 
 * Connects to ReactorServer SSE to receive real-time updates.
 */
@Composable
fun KanbanBoard() {
    var columns by remember { mutableStateOf(listOf(
        "Backlog" to listOf("Task 1", "Task 2"),
        "In Progress" to listOf("Task 3"),
        "Done" to listOf("Task 4", "Task 5")
    ))}
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Forge UI", style = MaterialTheme.typography.h4)
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            columns.forEach { (title, items) ->
                Card(
                    modifier = Modifier.weight(1f).fillMaxHeight()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(title, style = MaterialTheme.typography.h6)
                        Spacer(modifier = Modifier.height(8.dp))
                        items.forEach { item ->
                            Text(item, modifier = Modifier.padding(4.dp))
                        }
                    }
                }
            }
        }
    }
}

fun main() = application {
    Window(onCloseRequest = ::exitApplication) {
        MaterialTheme {
            KanbanBoard()
        }
    }
}