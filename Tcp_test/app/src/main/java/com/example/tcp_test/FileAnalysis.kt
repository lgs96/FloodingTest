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
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.BufferedSink
import java.io.IOException

private const val TAG = "FileAnalysis"

// Custom RequestBody that reports progress
class ProgressRequestBody(
    private val data: ByteArray,
    private val contentTypeVal: MediaType?,
    private val onProgress: (Float) -> Unit
) : RequestBody() {

    override fun contentType() = contentTypeVal

    override fun contentLength() = data.size.toLong()

    override fun writeTo(sink: BufferedSink) {
        val totalBytes = data.size.toLong()
        var bytesWritten = 0L
        val segmentSize = 2048
        var offset = 0
        while (offset < data.size) {
            val bytesToWrite = minOf(segmentSize, data.size - offset)
            sink.write(data, offset, bytesToWrite)
            offset += bytesToWrite
            bytesWritten += bytesToWrite
            onProgress(bytesWritten.toFloat() / totalBytes.toFloat())
        }
    }
}

class FileAnalyzer {
    suspend fun uploadFileForAnalysis(
        context: android.content.Context,
        fileUri: Uri,
        serverIp: String,
        serverPort: Int,
        onProgress: (Float) -> Unit
    ): String = withContext(Dispatchers.IO) {
        try {
            // Retrieve file details
            val documentFile = DocumentFile.fromSingleUri(context, fileUri)
            val fileName = documentFile?.name ?: "unknown"
            val inputStream = context.contentResolver.openInputStream(fileUri)
                ?: return@withContext "Error: Unable to open file"
            val fileBytes = inputStream.readBytes()
            inputStream.close()

            // Construct the HTTP URL for file upload
            val url = "http://$serverIp:$serverPort/upload_file"
            val mediaType = "application/octet-stream".toMediaTypeOrNull()
            val progressRequestBody = ProgressRequestBody(fileBytes, mediaType, onProgress)

            // Build the HTTP POST request with the file data and filename header
            val request = Request.Builder()
                .url(url)
                .post(progressRequestBody)
                .addHeader("X-Filename", fileName)
                .build()

            val client = OkHttpClient()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext "Server error: ${response.code}"
                }
                return@withContext response.body?.string() ?: "No response from server"
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

    // Load default server settings
    val prefs = context.getSharedPreferences("file_analysis_settings", android.content.Context.MODE_PRIVATE)
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

    // Save server settings when they change
    LaunchedEffect(serverIp, serverPort) {
        val sp = context.getSharedPreferences("file_analysis_settings", android.content.Context.MODE_PRIVATE)
        sp.edit().apply {
            putString("server_ip", serverIp)
            putString("server_port", serverPort)
            apply()
        }
    }

    // File picker for document selection
    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
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

        // Server configuration fields
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

        // File selection button
        Button(
            onClick = {
                val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                documentPicker.launch(intent)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Select File")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Display selected file name
        Text(
            text = "Selected: $fileName",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Display upload progress if uploading
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

        // Button to trigger file upload and analysis
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

        // Display analysis results when available
        if (analysisResult.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
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
