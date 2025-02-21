package com.example.tcp_test

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.Socket
import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.launch

private const val TAG = "FileAnalysis"

class FileAnalyzer {
    suspend fun uploadFileForAnalysis(
        context: android.content.Context,
        fileUri: Uri,
        serverIp: String,
        serverPort: Int,
        onProgress: (Float) -> Unit
    ): String = withContext(Dispatchers.IO) {
        try {
            // Get file details
            val documentFile = DocumentFile.fromSingleUri(context, fileUri)
            val fileName = documentFile?.name ?: "unknown"
            val fileSize = documentFile?.length() ?: 0L

            Socket(serverIp, serverPort).use { socket ->
                val output = socket.getOutputStream().buffered()
                val input = socket.getInputStream().bufferedReader()

                // Send file name and size
                output.write("$fileName\n".toByteArray())
                output.write("$fileSize\n".toByteArray())
                output.flush()

                // Read server's ready confirmation
                val readyResponse = input.readLine()
                if (readyResponse != "READY") {
                    return@withContext "Server not ready: $readyResponse"
                }

                // Send file content
                context.contentResolver.openInputStream(fileUri)?.use { inputStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        // Update progress
                        val progress = if (fileSize > 0) {
                            totalBytesRead.toFloat() / fileSize.toFloat()
                        } else 0f
                        onProgress(progress)
                    }
                    output.flush()
                }

                // Read analysis result
                val result = input.readLine() ?: "No response from server"
                return@withContext result
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading file", e)
            return@withContext "Error: ${e.message}"
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileAnalysisScreen(
    onBackPressed: () -> Unit
) {
    val context = LocalContext.current

    // Load default settings
    val prefs = context.getSharedPreferences("file_analysis_settings", Context.MODE_PRIVATE)
    val defaultIp = prefs.getString("server_ip", "") ?: ""
    val defaultPort = prefs.getString("server_port", "") ?: ""

    var serverIp by remember { mutableStateOf(defaultIp) }
    var serverPort by remember { mutableStateOf(defaultPort) }
    var selectedFile by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("No file selected") }
    var analysisResult by remember { mutableStateOf("") }
    var uploadProgress by remember { mutableStateOf(0f) }
    var isUploading by remember { mutableStateOf(false) }

    val fileAnalyzer = remember { FileAnalyzer() }

    // Save settings whenever they change
    LaunchedEffect(serverIp, serverPort) {
        val sp = context.getSharedPreferences("file_analysis_settings", Context.MODE_PRIVATE)
        sp.edit().apply {
            putString("server_ip", serverIp)
            putString("server_port", serverPort)
            apply()
        }
    }

    // File picker for documents
    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                selectedFile = uri
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            fileName = cursor.getString(nameIndex)
                        }
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Top bar with back button
        IconButton(
            onClick = onBackPressed,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
        }

        Text(
            text = "File Analysis",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // Server configuration
        OutlinedTextField(
            value = serverIp,
            onValueChange = { serverIp = it },
            label = { Text("Server IP") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = serverPort,
            onValueChange = { serverPort = it },
            label = { Text("Server Port") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // File selection
        Button(
            onClick = {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                documentPicker.launch(intent)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Select File")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Show selected file name
        Text(
            text = "Selected: $fileName",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Upload progress
        if (isUploading) {
            LinearProgressIndicator(
                progress = uploadProgress,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Uploading: ${(uploadProgress * 100).toInt()}%",
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        // Upload button
        Button(
            onClick = {
                selectedFile?.let { uri ->
                    isUploading = true
                    kotlinx.coroutines.MainScope().launch {
                        try {
                            val result = fileAnalyzer.uploadFileForAnalysis(
                                context,
                                uri,
                                serverIp,
                                serverPort.toIntOrNull() ?: 8888
                            ) { progress ->
                                uploadProgress = progress
                            }
                            analysisResult = result
                        } finally {
                            isUploading = false
                            uploadProgress = 0f
                        }
                    }
                }
            },
            enabled = selectedFile != null &&
                    serverIp.isNotEmpty() &&
                    serverPort.isNotEmpty() &&
                    !isUploading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Upload and Analyze")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Analysis results
        if (analysisResult.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Analysis Results:",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = analysisResult,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
