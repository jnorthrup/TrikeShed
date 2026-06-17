package borg.trikeshed.forge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.singleWindowApplication

@Composable
fun App() {
    val count = remember { mutableStateOf(0) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier.size(400.dp, 300.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1E1E1E),
                contentColor = Color.White
            )
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Forge UI",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(0.dp, 16.dp, 0.dp, 0.dp))
                Text(
                    text = "Kanban workspace powered by TrikeShed",
                    fontSize = 16.sp,
                    color = Color(0xFFAAAAAA)
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(0.dp, 32.dp, 0.dp, 0.dp))
                Text(
                    text = "Count: ${count.value}",
                    fontSize = 24.sp
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(0.dp, 16.dp, 0.dp, 0.dp))
                Button(onClick = { count.value++ }) {
                    Text(text = "Increment", fontSize = 18.sp)
                }
            }
        }
    }
}

fun main() = singleWindowApplication { App() }