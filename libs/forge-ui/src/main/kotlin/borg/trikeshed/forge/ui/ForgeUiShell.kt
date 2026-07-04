package borg.trikeshed.forge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun ForgeUiShell() {
    var panel by remember { mutableStateOf(ForgePanel.Showcase) }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Forge UI", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Widget showcase + board workspace + movie-ready layouts",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ShellTabButton(
                                label = "Showcase",
                                active = panel == ForgePanel.Showcase,
                                onClick = { panel = ForgePanel.Showcase },
                            )
                            ShellTabButton(
                                label = "Board",
                                active = panel == ForgePanel.Board,
                                onClick = { panel = ForgePanel.Board },
                            )
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    when (panel) {
                        ForgePanel.Showcase -> WidgetShowcaseScreen()
                        ForgePanel.Board -> KanbanBoardScreen()
                    }
                }
            }
        }
    }
}

private enum class ForgePanel { Showcase, Board }

@Composable
private fun ShellTabButton(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    if (active) {
        FilledTonalButton(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}
