package com.example.tcp_test

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TcpUdpTestScreen(
    defaultMode: String = "TCP",
    defaultIp: String = "",
    defaultControlPort: String = "8889",
    defaultDataPort: String = "8890",
    defaultRate: String = "500Mbps",
    onBackPressed: () -> Unit
) {
    var transportMode by remember { mutableStateOf(defaultMode) }
    var serverIp by remember { mutableStateOf(defaultIp) }
    var controlPort by remember { mutableStateOf(defaultControlPort) }
    var dataPort by remember { mutableStateOf(defaultDataPort) }
    var desiredRate by remember { mutableStateOf(defaultRate) }

    val context = LocalContext.current
    val tester = remember { TcpUdpTester() }

    // Save settings whenever they change
    LaunchedEffect(transportMode, serverIp, controlPort, dataPort, desiredRate) {
        saveSettings(context, transportMode, serverIp, controlPort, dataPort, desiredRate)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        IconButton(
            onClick = onBackPressed,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
        }

        Text(
            text = "TCP/UDP Performance Test",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    transportMode = "TCP"
                    saveSettings(context, transportMode, serverIp, controlPort, dataPort, desiredRate)
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (transportMode == "TCP") "TCP (Selected)" else "TCP")
            }
            Button(
                onClick = {
                    transportMode = "UDP"
                    saveSettings(context, transportMode, serverIp, controlPort, dataPort, desiredRate)
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (transportMode == "UDP") "UDP (Selected)" else "UDP")
            }
        }

        OutlinedTextField(
            value = serverIp,
            onValueChange = { serverIp = it },
            label = { Text("Server IP") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        )

        OutlinedTextField(
            value = controlPort,
            onValueChange = { controlPort = it },
            label = { Text("Control Port") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        )

        OutlinedTextField(
            value = dataPort,
            onValueChange = { dataPort = it },
            label = { Text("Data Port") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        )

        OutlinedTextField(
            value = desiredRate,
            onValueChange = { desiredRate = it },
            label = { Text("Desired Rate (e.g. 500Mbps)") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = {
                    saveSettings(context, transportMode, serverIp, controlPort, dataPort, desiredRate)
                    tester.startTest(
                        context,
                        transportMode,
                        serverIp,
                        controlPort.toIntOrNull() ?: 8889,
                        dataPort.toIntOrNull() ?: 8890,
                        desiredRate
                    )
                }
            ) {
                Text("Start Test")
            }
            Button(
                onClick = {
                    tester.stopTest(transportMode, serverIp, controlPort)
                }
            ) {
                Text("Stop Test")
            }
        }

        Text(
            text = "Throughput: ${tester.throughput} Mbps",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(vertical = 16.dp)
        )
    }
}

private fun saveSettings(
    context: Context,
    mode: String,
    ip: String,
    cport: String,
    dport: String,
    rate: String
) {
    val sp = context.getSharedPreferences("tcp_test_settings", Context.MODE_PRIVATE)
    sp.edit().apply {
        putString("transport_mode", mode)
        putString("server_ip", ip)
        putString("control_port", cport)
        putString("data_port", dport)
        putString("desired_rate", rate)
        apply()
    }
}