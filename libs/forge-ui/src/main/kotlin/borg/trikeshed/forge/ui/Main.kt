package borg.trikeshed.forge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Error
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.singleWindowApplication
import borg.trikeshed.forge.kanban.v2.CoordinatorConfig
import borg.trikeshed.forge.kanban.v2.KanbanElements
import borg.trikeshed.forge.kanban.v2.installKanban
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@Composable
fun ForgeApp() {
    // Install Kanban CCEK elements in background
    val kanbanScope = remember { CoroutineScope(Dispatchers.Default) }
    var kanbanElements by remember { mutableStateOf<KanbanElements?>(null) }
    var initError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            val elements = installKanban(
                config = CoordinatorConfig(maxInProgress = 5, maxSpawn = 3),
                maxHistoryPerKey = 500
            )
            kanbanElements = elements
            // Start coordinator loop
            kanbanScope.launch { elements.coordinator.startLoop(kanbanScope) }
        } catch (e: Exception) {
            initError = e.message
        }
    }

    // Collect reactive state
    val coordinatorState by (kanbanElements?.coordinator?.flowState
        ?.collectAsState(initial = null) ?: remember { mutableStateOf(null) })
    var keyPoolStats by remember { mutableStateOf<String>("") }

    // Load key pool stats periodically
    LaunchedEffect(kanbanElements) {
        val elements = kanbanElements
        if (elements != null) {
            kanbanScope.launch {
                while (true) {
                    val draft = elements.keyPool.getDraft()
                    val available = elements.keyPool.getAvailable().size
                    keyPoolStats = "Keys: ${draft.mapping.size} drafted, $available available"
                    delay(5000)
                }
            }
        }
    }

    // UI State
    var showConfig by remember { mutableStateOf(false) }
    var maxInProgress by remember { mutableStateOf(5) }
    var maxSpawn by remember { mutableStateOf(3) }

    if (initError != null) {
        ErrorScreen(error = initError!!)
        return
    }

    if (kanbanElements == null) {
        LoadingScreen()
        return
    }

    val elements = kanbanElements!!

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF1A1A2E)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Header(title = "Forge Kanban", subtitle = "CCEK-powered autonomous dispatch", showConfig = { showConfig = true })

            HorizontalDivider(color = Color(0xFF333355), thickness = 1.dp)

            // Main content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Key Pool Status
                StatusCard(
                    title = "Key Pool",
                    value = keyPoolStats,
                    icon = Icons.Default.Settings,
                    color = Color(0xFF00D4FF)
                )

                // Coordinator State
                CoordinatorStateCard(state = coordinatorState)

                // Controls
                ControlsCard(
                    maxInProgress = maxInProgress,
                    maxSpawn = maxSpawn,
                    onMaxInProgressChange = { maxInProgress = it },
                    onMaxSpawnChange = { maxSpawn = it },
                    onApply = {
                        elements.coordinator.updateConfig(
                            CoordinatorConfig(maxInProgress = maxInProgress, maxSpawn = maxSpawn)
                        )
                    },
                    onRunTick = { elements.coordinator.tick() },
                )
            }
        }
    }
}

@Composable
fun Header(title: String, subtitle: String, showConfig: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(text = title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(text = subtitle, fontSize = 14.sp, color = Color(0xFFAAAACC))
        }
        IconButton(onClick = showConfig) {
            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color(0xFF00D4FF))
        }
    }
}

@Composable
fun StatusCard(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF16213E),
            contentColor = Color.White
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(32.dp))
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(16.dp, 0.dp, 0.dp, 0.dp))
            Column {
                Text(text = title, fontSize = 14.sp, color = Color(0xFFAAAACC))
                Text(text = value, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun CoordinatorStateCard(state: borg.trikeshed.forge.kanban.v2.CoordinatorState?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF16213E),
            contentColor = Color.White
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Coordinator Dispatch", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(0.dp, 8.dp, 0.dp, 0.dp))
            if (state != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricBox("Running", "${state.currentlyRunning} / ${state.maxInProgress}", Colors.Green)
                    MetricBox("Available Keys", state.availableKeys.toString(), Colors.Blue)
                    MetricBox("Queue Depth", state.queueDepth.toString(), Colors.Orange)
                    MetricBox("Max Spawn/Tick", state.maxSpawn.toString(), Colors.Purple)
                }
            } else {
                Text(text = "Initializing...", color = Color(0xFF666688))
            }
        }
    }
}

@Composable
fun RowScope.MetricBox(label: String, value: String, color: Color) {
    Column(
        modifier = Modifier
            .weight(1f)
            .padding(8.dp)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = label, fontSize = 12.sp, color = Color(0xFF8888AA))
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(0.dp, 4.dp, 0.dp, 0.dp))
        Text(text = value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

object Colors {
    val Green = Color(0xFF00E676)
    val Blue = Color(0xFF00D4FF)
    val Orange = Color(0xFFFFB300)
    val Purple = Color(0xFFBB86FC)
    val Gray = Color(0xFF8888AA)
}

@Composable
fun ControlsCard(
    maxInProgress: Int,
    maxSpawn: Int,
    onMaxInProgressChange: (Int) -> Unit,
    onMaxSpawnChange: (Int) -> Unit,
    onApply: () -> Unit,
    onRunTick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF16213E),
            contentColor = Color.White
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Controls", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(0.dp, 16.dp, 0.dp, 0.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Max In Progress", fontSize = 14.sp, color = Color(0xFFAAAACC))
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(0.dp, 8.dp, 0.dp, 0.dp))
                    TextField(
                        value = maxInProgress.toString(),
                        onValueChange = { v -> v.toIntOrNull()?.let(onMaxInProgressChange) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Max Spawn/Tick", fontSize = 14.sp, color = Color(0xFFAAAACC))
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(0.dp, 8.dp, 0.dp, 0.dp))
                    TextField(
                        value = maxSpawn.toString(),
                        onValueChange = { v -> v.toIntOrNull()?.let(onMaxSpawnChange) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }

            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(0.dp, 16.dp, 0.dp, 0.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = onRunTick,
                    modifier = Modifier.weight(1f),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00D4FF)
                    )
                ) {
                    Text(text = "Run Dispatch Tick", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onApply,
                    modifier = Modifier.weight(1f),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00E676)
                    )
                ) {
                    Text(text = "Apply Config", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun ConfigDialog(
    maxInProgress: Int,
    maxSpawn: Int,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp)
            .height(300.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1A1A2E),
            contentColor = Color.White
        )
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(text = "Coordinator Configuration", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(0.dp, 24.dp, 0.dp, 0.dp))

            Column(modifier = Modifier.fillMaxWidth()) {
                Text(text = "Max In Progress: $maxInProgress", fontSize = 16.sp)
                Text(text = "Max Spawn/Tick: $maxSpawn", fontSize = 16.sp)
                Text(text = "Lease TTL: 300000ms", fontSize = 16.sp)
                Text(text = "Tick Interval: 5000ms", fontSize = 16.sp)
            }

            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(0.dp, 32.dp, 0.dp, 0.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(onClick = onCancel) { Text(text = "Cancel") }
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(8.dp, 0.dp, 0.dp, 0.dp))
                Button(onClick = onApply, colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))) {
                    Text(text = "Apply")
                }
            }
        }
    }
}

@Composable
fun LoadingScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(color = Color(0xFF00D4FF), modifier = Modifier.size(48.dp))
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(0.dp, 16.dp, 0.dp, 0.dp))
        Text(text = "Initializing Forge CCEK...", fontSize = 18.sp, color = Color(0xFFAAAACC))
    }
}

@Composable
fun ErrorScreen(error: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Error, contentDescription = "Error", tint = Color(0xFFFF5252), modifier = Modifier.size(64.dp))
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(0.dp, 16.dp, 0.dp, 0.dp))
        Text(text = "Failed to initialize", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(0.dp, 8.dp, 0.dp, 0.dp))
        Text(text = error, fontSize = 14.sp, color = Color(0xFF8888AA), textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
    }
}

fun main() = singleWindowApplication {
    ForgeApp()
}
