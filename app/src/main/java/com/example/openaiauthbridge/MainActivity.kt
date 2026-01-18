package com.example.openaiauthbridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URL

class MainActivity : AppCompatActivity() {

    private var serverThread: Thread? = null
    private var cloudflareThread: Thread? = null
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var cloudflareProcess: Process? = null
    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var vpsUrlInput: EditText
    private lateinit var toggleButton: Button
    private lateinit var phoneIpText: TextView
    private lateinit var cloudflareUrlText: TextView
    private lateinit var copyButton: Button
    private var cloudflareUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("AuthBridgePrefs", Context.MODE_PRIVATE)

        statusText = findViewById(R.id.statusText)
        vpsUrlInput = findViewById(R.id.vpsUrlInput)
        toggleButton = findViewById(R.id.toggleButton)
        phoneIpText = findViewById(R.id.phoneIpText)
        cloudflareUrlText = findViewById(R.id.cloudflareUrlText)
        copyButton = findViewById(R.id.copyButton)

        vpsUrlInput.setText(prefs.getString("vps_url", ""))

        phoneIpText.text = "Phone IP: ${getLocalIpAddress()}"

        toggleButton.setOnClickListener {
            if (isRunning) {
                stopServer()
            } else {
                startServer()
            }
        }

        copyButton.setOnClickListener {
            copyCloudflareUrl()
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
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
        toggleButton.text = "Stop Server"
        toggleButton.setBackgroundColor(getColor(android.R.color.holo_red_dark))
        cloudflareUrlText.text = "Starting CloudFlare tunnel..."
        cloudflareUrlText.visibility = android.view.View.VISIBLE
        copyButton.visibility = android.view.View.VISIBLE
        statusText.text = "🚀 Starting OAuth bridge...\n\nThis may take 10-20 seconds."

        // Start CloudFlare tunnel in background
        cloudflareThread = Thread {
            try {
                // Check if cloudflared exists
                val checkProcess = Runtime.getRuntime().exec("which cloudflared")
                val checkReader = BufferedReader(InputStreamReader(checkProcess.inputStream))
                val hasCloudflared = checkReader.readLine() != null

                if (!hasCloudflared) {
                    runOnUiThread {
                        statusText.text = "⚠️ CloudFlare not installed\n\nInstalling via Termux command:\n\npkg install cloudflared"
                    }
                    return@Thread
                }

                // Start cloudflared tunnel
                cloudflareProcess = Runtime.getRuntime().exec("cloudflared tunnel --url http://localhost:1455")

                // Read output for URL
                val reader = BufferedReader(InputStreamReader(cloudflareProcess!!.inputStream))
                var line: String?
                while (isRunning) {
                    line = reader.readLine()
                    if (line != null && line.contains("trycloudflare.com")) {
                        cloudflareUrl = line.trim()
                        runOnUiThread {
                            cloudflareUrlText.text = cloudflareUrl
                            statusText.text = "✅ Tunnel ready!\n\nAuto-configuring OpenCode on VPS..."
                        }
                        // Auto-send URL to VPS
                        sendUrlToVps(vpsUrl, cloudflareUrl)
                    }
                    if (line != null && line.contains("INF")) {
                        runOnUiThread {
                            statusText.text = "🌐 Tunnel: $cloudflareUrl\n\nWaiting for OAuth callback..."
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Error: ${e.message}"
                }
            }
        }
        cloudflareThread?.start()

        // Start local server
        serverThread = Thread {
            try {
                serverSocket = ServerSocket(1455)
                while (isRunning) {
                    val client = serverSocket?.accept()
                    if (client != null) {
                        handleRequest(client, vpsUrl)
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

    private fun sendUrlToVps(vpsUrl: String, url: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Send cloudflare URL to VPS auto-config endpoint
                val configUrl = "$vpsUrl/opencode/configure?url=${java.net.URLEncoder.encode(url, "UTF-8")}"
                val conn = URL(configUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                
                val response = conn.inputStream.bufferedReader().readText()
                withContext(Dispatchers.Main) {
                    statusText.text = "✅ Configured!\n\nCloudFlare URL: $url\n\nVPS Response: $response\n\nReady for OAuth!"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "✅ Tunnel ready: $url\n\nAuto-config failed: ${e.message}\n\nManual config needed on VPS."
                }
            }
        }
    }

    private fun handleRequest(client: java.net.Socket, vpsUrl: String) {
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val request = reader.readText()

            if (request.contains("code=") || request.contains("error=")) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val response = forwardToVPS(vpsUrl, request)
                        withContext(Dispatchers.Main) {
                            statusText.text = "✅ Auth complete!\n\nResponse: ${response.take(300)}"
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            statusText.text = "Forward error: ${e.message}"
                        }
                    }
                }
            }

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
                        .container { background: white; padding: 30px; border-radius: 10px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); max-width: 400px; margin: 0 auto; }
                        h1 { color: #4CAF50; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <h1>✅ Authorization Complete</h1>
                        <p>Auth code forwarded to VPS!</p>
                        <p>Check your terminal.</p>
                    </div>
                </body>
                </html>
            """.trimIndent()

            val output: OutputStream = client.getOutputStream()
            output.write(response.toByteArray())
            output.flush()
        } catch (e: Exception) {
        } finally {
            try { client.close() } catch (e: Exception) {}
        }
    }

    private fun forwardToVPS(vpsUrl: String, request: String): String {
        val urlMatch = Regex("GET (\\S+) HTTP").find(request)
        val callbackPath = urlMatch?.groupValues?.get(1) ?: ""
        val fullUrl = "$vpsUrl$callbackPath"
        val url = URL(fullUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        return conn.inputStream.bufferedReader().readText()
    }

    private fun copyCloudflareUrl() {
        if (cloudflareUrl.isNotEmpty()) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("CloudFlare URL", cloudflareUrl)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "URL copied!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopServer() {
        isRunning = false
        try {
            cloudflareProcess?.destroy()
            serverSocket?.close()
        } catch (e: Exception) {}
        serverThread?.interrupt()
        cloudflareThread?.interrupt()

        runOnUiThread {
            toggleButton.text = "Start Server"
            toggleButton.setBackgroundColor(getColor(android.R.color.holo_green_dark))
            cloudflareUrlText.visibility = android.view.View.GONE
            copyButton.visibility = android.view.View.GONE
            statusText.text = "Server Stopped"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
    }
}
