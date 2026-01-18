package com.example.openaiauthbridge

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.net.InetAddress

class MainActivity : AppCompatActivity() {

    private var serverThread: Thread? = null
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var vpsUrlInput: EditText
    private lateinit var toggleButton: Button
    private lateinit var phoneIpText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("AuthBridgePrefs", Context.MODE_PRIVATE)

        statusText = findViewById(R.id.statusText)
        vpsUrlInput = findViewById(R.id.vpsUrlInput)
        toggleButton = findViewById(R.id.toggleButton)
        phoneIpText = findViewById(R.id.phoneIpText)

        // Load saved VPS URL
        vpsUrlInput.setText(prefs.getString("vps_url", ""))

        // Display phone's IP
        phoneIpText.text = "Phone IP: " + getLocalIpAddress()

        toggleButton.setOnClickListener {
            if (isRunning) {
                stopServer()
            } else {
                startServer()
            }
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = java.network.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address.hostAddress?.contains('.') == true) {
                        return address.hostAddress!!
                    }
                }
            }
        } catch (e: Exception) {
            return "Error: ${e.message}"
        }
        return "Unknown"
    }

    private fun startServer() {
        val vpsUrl = vpsUrlInput.text.toString().trim()
        if (vpsUrl.isEmpty()) {
            statusText.text = "Please enter VPS URL first"
            return
        }
        prefs.edit().putString("vps_url", vpsUrl).apply()

        isRunning = true
        toggleButton.text = getString(R.string.stop_server)

        val phoneIp = getLocalIpAddress()
        val callbackUrl = "http://$phoneIp:1455/auth/callback"
        statusText.text = "Server Running!\n\nOAuth Callback URL:\n$callbackUrl\n\nCopy this URL and use it as redirect_uri"

        serverThread = Thread {
            try {
                serverSocket = ServerSocket(1455)
                while (isRunning) {
                    val client = serverSocket?.accept()
                    if (client != null) {
                        handleRequest(client, vpsUrl, callbackUrl)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    runOnUiThread {
                        statusText.text = "Error: ${e.message}"
                    }
                }
            }
        }
        serverThread?.start()
    }

    private fun handleRequest(client: java.net.Socket, vpsUrl: String, callbackUrl: String) {
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val request = reader.readText()

            // Check if this is the OAuth callback
            if (request.contains("code=") || request.contains("error=")) {
                // Forward to VPS
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val response = forwardToVPS(vpsUrl, request)
                        withContext(Dispatchers.Main) {
                            statusText.text = "✅ Auth forwarded to VPS!\n\nResponse: ${response.take(200)}"
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            statusText.text = "Forward error: ${e.message}\n\nCallback URL: $callbackUrl"
                        }
                    }
                }
            }

            // Send success response to browser
            val response = """
                HTTP/1.1 200 OK
                Content-Type: text/html
                Connection: close

                <!DOCTYPE html>
                <html>
                <head>
                    <title>Auth Complete</title>
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <style>
                        body { font-family: Arial, sans-serif; text-align: center; padding: 50px; background: #f5f5f5; }
                        .container { background: white; padding: 30px; border-radius: 10px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
                        h1 { color: #4CAF50; }
                        p { color: #666; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <h1>✅ Authorization Complete</h1>
                        <p>The auth code has been forwarded to your VPS.</p>
                        <p>You can close this page and return to your terminal.</p>
                    </div>
                </body>
                </html>
            """.trimIndent()

            val output: OutputStream = client.getOutputStream()
            output.write(response.toByteArray())
            output.flush()
        } catch (e: Exception) {
            // Silently handle errors
        } finally {
            try { client.close() } catch (e: Exception) {}
        }
    }

    private fun forwardToVPS(vpsUrl: String, request: String): String {
        // Extract the callback URL with parameters
        val urlMatch = Regex("GET (\\S+) HTTP").find(request)
        val callbackPath = urlMatch?.groupValues?.get(1) ?: ""

        // Forward to VPS
        val fullUrl = "$vpsUrl$callbackPath"
        val url = URL(fullUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10000
        conn.readTimeout = 10000

        return conn.inputStream.bufferedReader().readText()
    }

    private fun stopServer() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        serverThread?.interrupt()

        runOnUiThread {
            toggleButton.text = getString(R.string.start_server)
            statusText.text = "Server Stopped\n\nPhone IP: ${getLocalIpAddress()}"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
    }
}
