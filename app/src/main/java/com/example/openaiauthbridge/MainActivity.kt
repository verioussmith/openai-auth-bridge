package com.example.openaiauthbridge

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
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
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var vpsUrlInput: EditText
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var phoneIpText: TextView
    
    private var serverThread: Thread? = null
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var oauthComplete = false
    
    private var vpsUrl: String = ""
    private var receivedCode: String? = null
    private var receivedState: String? = null
    
    // OAuth constants (from opencode plugin)
    private val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    private val AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("AuthBridgePrefs", Context.MODE_PRIVATE)

        vpsUrlInput = findViewById(R.id.vpsUrlInput)
        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.configureButton)
        phoneIpText = findViewById(R.id.phoneIpText)

        vpsUrlInput.setText(prefs.getString("vps_url", ""))
        phoneIpText.text = "Phone IP: ${getLocalIpAddress()}"

        startButton.setOnClickListener {
            startOAuth()
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

    private fun startOAuth() {
        vpsUrl = vpsUrlInput.text.toString().trim()
        if (vpsUrl.isEmpty()) {
            statusText.text = "Enter VPS URL first"
            return
        }
        
        prefs.edit().putString("vps_url", vpsUrl).apply()
        
        // Start local server to receive callback
        startLocalServer()
        
        // Generate OAuth URL
        val (codeVerifier, codeChallenge) = generatePKCE()
        val state = generateState()
        val redirectUri = "http://${getLocalIpAddress()}:1455/auth/callback"
        
        val oauthUrl = buildOAuthUrl(codeChallenge, state, redirectUri)
        
        statusText.text = "Opening OAuth login..."
        
        // Open in phone's browser (residential IP - no Cloudflare block)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(oauthUrl))
        startActivity(intent)
    }

    private fun generatePKCE(): Pair<String, String> {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val random = java.security.SecureRandom()
        val codeVerifier = (1..128).map { chars[random.nextInt(chars.length)] }.joinToString("")
        
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(codeVerifier.toByteArray())
        val codeChallenge = android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE).replace("=", "")
        
        return Pair(codeVerifier, codeChallenge)
    }

    private fun generateState(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = java.security.SecureRandom()
        return (1..32).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    private fun buildOAuthUrl(codeChallenge: String, state: String, redirectUri: String): String {
        val sb = StringBuilder(AUTHORIZE_URL)
        sb.append("?response_type=code")
        sb.append("&client_id=$CLIENT_ID")
        sb.append("&redirect_uri=${Uri.encode(redirectUri)}")
        sb.append("&scope=openid+profile+email+offline_access")
        sb.append("&code_challenge=$codeChallenge")
        sb.append("&code_challenge_method=S256")
        sb.append("&state=$state")
        sb.append("&id_token_add_organizations=true")
        sb.append("&codex_cli_simplified_flow=true")
        sb.append("&originator=codex_cli_rs")
        return sb.toString()
    }

    private fun startLocalServer() {
        isRunning.set(true)
        
        serverThread = Thread {
            try {
                serverSocket = ServerSocket(1455)
                
                while (isRunning.get() && !oauthComplete) {
                    try {
                        val client = serverSocket?.accept()
                        if (client != null && !oauthComplete) {
                            handleClient(client)
                            client.close()
                        }
                    } catch (e: Exception) {
                        if (isRunning.get()) {}
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    runOnUiThread {
                        statusText.text = "Server error: ${e.message}"
                    }
                }
            }
        }
        serverThread?.start()
    }

    private fun handleClient(client: java.net.Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val request = reader.readText()
            
            if (request.contains("code=") || request.contains("error=")) {
                // Extract code and state
                val codeMatch = Regex("code=([^&\\s]+)").find(request)
                val stateMatch = Regex("state=([^&\\s]+)").find(request)
                
                receivedCode = codeMatch?.groupValues?.get(1)
                receivedState = stateMatch?.groupValues?.get(1)
                
                if (receivedCode != null) {
                    oauthComplete = true
                    runOnUiThread {
                        statusText.text = "✅ Code received! Sending to VPS..."
                    }
                    
                    stopLocalServer()
                    sendCodeToVps()
                }
            }
            
            // Send response to browser
            val response = """
                HTTP/1.1 200 OK
                Content-Type: text/html
                Connection: close
                
                <!DOCTYPE html>
                <html>
                <head>
                    <title>Auth Complete</title>
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                </head>
                <body style="font-family: Arial; text-align: center; padding: 50px; background: #f5f5f5;">
                    <div style="background: white; padding: 30px; border-radius: 10px; max-width: 400px; margin: 0 auto;">
                        <h1 style="color: #4CAF50;">✅ Authorization Complete</h1>
                        <p>Code sent to VPS!</p>
                        <p style="color: #666;">You can close this page.</p>
                    </div>
                </body>
                </html>
            """.trimIndent()
            
            val output: OutputStream = client.getOutputStream()
            output.write(response.toByteArray())
            output.flush()
        } catch (e: Exception) {}
    }

    private fun sendCodeToVps() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val phoneIp = getLocalIpAddress()
                val callbackUrl = "http://$phoneIp:1455/auth/callback?code=$receivedCode&state=$receivedState"
                val fullUrl = "$vpsUrl$callbackUrl"
                
                val url = URL(fullUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 30000
                conn.readTimeout = 30000
                
                val response = conn.inputStream.bufferedReader().readText()
                
                withContext(Dispatchers.Main) {
                    statusText.text = "✅ Done!\n\nOpenCode is configured.\n\nCheck your terminal!"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "⚠️ Code received but couldn't send to VPS.\n\nManual step: Copy this URL to terminal:\n\nhttp://${getLocalIpAddress()}:1455/auth/callback?code=$receivedCode&state=$receivedState"
                }
            }
        }
    }

    private fun stopLocalServer() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        serverThread?.interrupt()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocalServer()
    }
}
