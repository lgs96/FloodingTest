package com.example.tcp_test

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.tcp_test.ui.theme.Tcp_testTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load default settings for TCP/UDP test
        val prefs = getSharedPreferences("tcp_test_settings", MODE_PRIVATE)
        val defaultMode = prefs.getString("transport_mode", "TCP") ?: "TCP"
        val defaultIp = prefs.getString("server_ip", "") ?: ""
        val defaultControlPort = prefs.getString("control_port", "8889") ?: "8889"
        val defaultDataPort = prefs.getString("data_port", "8890") ?: "8890"
        val defaultRate = prefs.getString("desired_rate", "100Mbps") ?: "100Mbps"

        setContent {
            Tcp_testTheme {
                var currentScreen by remember { mutableStateOf<Screen>(Screen.MainMenu) }

                when (currentScreen) {
                    Screen.MainMenu -> MainMenu(
                        onNavigateToTcpUdpTest = { currentScreen = Screen.TcpUdpTest },
                        onNavigateToFileAnalysis = { currentScreen = Screen.FileAnalysis }
                    )
                    Screen.TcpUdpTest -> TcpUdpTestScreen(
                        defaultMode = defaultMode,
                        defaultIp = defaultIp,
                        defaultControlPort = defaultControlPort,
                        defaultDataPort = defaultDataPort,
                        defaultRate = defaultRate,
                        onBackPressed = { currentScreen = Screen.MainMenu }
                    )
                    Screen.FileAnalysis -> FileAnalysisScreen(
                        onBackPressed = { currentScreen = Screen.MainMenu }
                    )
                }
            }
        }
    }
}

sealed class Screen {
    object MainMenu : Screen()
    object TcpUdpTest : Screen()
    object FileAnalysis : Screen()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainMenu(
    onNavigateToTcpUdpTest: () -> Unit,
    onNavigateToFileAnalysis: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Network Testing Tools",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        Button(
            onClick = onNavigateToTcpUdpTest,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Text("TCP/UDP Test")
        }

        Button(
            onClick = onNavigateToFileAnalysis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Text("File Analysis")
        }
    }
}