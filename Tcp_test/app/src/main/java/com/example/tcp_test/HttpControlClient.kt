// HttpControlClient.kt
package com.example.tcp_test

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.IOException

object HttpControlClient {
    private val TAG = "HttpControlClient"
    private val client = OkHttpClient()

    suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "GET $url failed: ${response.code}")
                    return@withContext null
                }
                return@withContext response.body?.string()
            }
        } catch (e: IOException) {
            Log.e(TAG, "GET $url error", e)
            null
        }
    }

    suspend fun post(url: String, bodyText: String): String? = withContext(Dispatchers.IO) {
        val mediaType = "text/plain; charset=utf-8".toMediaTypeOrNull()
        val body = RequestBody.create(mediaType, bodyText)
        val request = Request.Builder().url(url).post(body).build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "POST $url failed: ${response.code}")
                    return@withContext null
                }
                return@withContext response.body?.string()
            }
        } catch (e: IOException) {
            Log.e(TAG, "POST $url error", e)
            null
        }
    }
}
