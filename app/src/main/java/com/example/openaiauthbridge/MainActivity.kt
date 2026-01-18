package com.example.openaiauthbridge

import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var webView: WebView

    private var vpsUrl: String = ""
    private var receivedCode: String? = null
    private var serverThread: Thread? = null
    private var serverSocket: ServerSocket? = null
    private var isRunning = false

    private val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    private val AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        webView = findViewById(R.id.webView)

        setupWebView()

        statusText.text = "Tap to configure"
        statusText.setOnClickListener {
            startOAuth()
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }

    private fun handleIntent(intent: android.content.Intent) {
        val uri = intent.data
        if (uri != null && uri.scheme == "openai-auth-bridge") {
            vpsUrl = uri.getQueryParameter("url") ?: ""
            if (vpsUrl.isNotEmpty()) {
                startOAuth()
            }
        }
    }

    private fun setupWebView() {
        val webSettings: WebSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (url.contains("code=") && (url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost"))) {
                    extractCodeFromUrl(url)
                    return true
                }
                return false
            }
        }
    }

    private fun startLocalServer() {
        isRunning = true
        serverThread = Thread {
            try {
                serverSocket = ServerSocket(1455)
                while (isRunning) {
                    try {
                        val client = serverSocket?.accept()
                        client?.use { c ->
                            val reader = c.getInputStream().bufferedReader()
                            val request = reader.readText()

                            if (request.contains("code=")) {
                                val codeMatch = Regex("code=([^&\\s]+)").find(request)
                                val code = codeMatch?.groupValues?.get(1)
                                if (code != null) {
                                    receivedCode = code
                                    runOnUiThread {
                                        statusText.text = "Code received! Sending to VPS..."
                                    }
                                    sendCodeToVps()
                                }
                            }

                            val response = """
                                HTTP/1.1 200 OK
                                Content-Type: text/html
                                Connection: close

                                <!DOCTYPE html>
                                <html>
                                <head>
                                    <meta charset="UTF-8">
                                    <meta name="viewport" content="width=device-width, initial-scale=1">
                                    <title>Authorization Complete</title>
                                </head>
                                <body style="font-family: system-ui, -apple-system, sans-serif; text-align: center; padding: 40px 20px; background: #f5f5f5;">
                                    <div style="max-width: 400px; margin: 0 auto; background: white; padding: 30px; border-radius: 12px; box-shadow: 0 2px 10px rgba(0,0,0,0.1);">
                                        <h1 style="color: #10a37f; margin: 0 0 16px 0;">Authorization Complete</h1>
                                        <p style="color: #666; margin: 0;">Code sent to your server!</p>
                                    </div>
                                </body>
                                </html>
                            """.trimIndent()
                            c.getOutputStream().write(response.toByteArray())
                            c.getOutputStream().flush()
                        }
                    } catch (e: Exception) {
                        if (isRunning) e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Server error: ${e.message}"
                }
            }
        }
        serverThread?.start()
    }

    private fun stopLocalServer() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        serverThread?.interrupt()
    }

    private fun extractCodeFromUrl(url: String) {
        try {
            val codeMatch = Regex("code=([^&]+)").find(url)
            val code = codeMatch?.groupValues?.get(1)
            if (code != null) {
                receivedCode = code
                statusText.text = "Code received! Sending to VPS..."
                sendCodeToVps()
            }
        } catch (e: Exception) {
            statusText.text = "Error: ${e.message}"
        }
    }

    private fun startOAuth() {
        statusText.text = "Starting..."
        webView.visibility = android.view.View.VISIBLE

        startLocalServer()

        val redirectUri = "http://127.0.0.1:1455/auth/callback"

        val (codeChallenge, state) = generateOAuthParams()

        statusText.text = "Opening ChatGPT..."

        val oauthUrl = buildOAuthUrl(codeChallenge, state, redirectUri)
        webView.loadUrl(oauthUrl)
    }

    private fun generateOAuthParams(): Pair<String, String> {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val random = java.security.SecureRandom()
        val codeVerifier = (1..128).map { chars[random.nextInt(chars.length)] }.joinToString("")

        val md = java.security.MessageDigest.getInstance("SHA-256")
        val hash = md.digest(codeVerifier.toByteArray())
        val codeChallenge = android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE).replace("=", "")

        val stateChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val state = (1..32).map { stateChars[random.nextInt(stateChars.length)] }.joinToString("")

        return Pair(codeChallenge, state)
    }

    private fun buildOAuthUrl(codeChallenge: String, state: String, redirectUri: String): String {
        return buildString {
            append(AUTHORIZE_URL)
            append("?response_type=code")
            append("&client_id=$CLIENT_ID")
            append("&redirect_uri=${java.net.URLEncoder.encode(redirectUri, "UTF-8")}")
            append("&scope=openid+profile+email+offline_access")
            append("&code_challenge=$codeChallenge")
            append("&code_challenge_method=S256")
            append("&state=$state")
            append("&id_token_add_organizations=true")
            append("&codex_cli_simplified_flow=true")
            append("&originator=codex_cli_rs")
        }
    }

    private fun sendCodeToVps() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val fullUrl = "$vpsUrl/auth/callback?code=$receivedCode"
                val url = URL(fullUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 30000
                conn.readTimeout = 30000

                val response = conn.inputStream.bufferedReader().readText()

                withContext(Dispatchers.Main) {
                    statusText.text = "Done! OpenCode is configured."
                    stopLocalServer()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "Error: ${e.message}\n\nCode: $receivedCode"
                    stopLocalServer()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocalServer()
    }
}
