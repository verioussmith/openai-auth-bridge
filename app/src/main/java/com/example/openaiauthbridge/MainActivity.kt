package com.example.openaiauthbridge

import android.content.Intent
import android.net.Uri
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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var webView: WebView

    private var serverThread: Thread? = null
    private var serverSocket: ServerSocket? = null
    private var isRunning = false

    private val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    private val AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize"
    private val REDIRECT_PORT = 1455

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        webView = findViewById(R.id.webView)

        setupWebView()

        statusText.text = "Tap to start OAuth server"
        statusText.setOnClickListener {
            startOAuth()
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }

    private fun handleIntent(intent: Intent) {
        val uri = intent.data
        if (uri != null && uri.scheme == "openai-auth-bridge") {
            startOAuth()
        }
    }

    private fun setupWebView() {
        val webSettings: WebSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.allowFileAccess = true
    }

    private fun startLocalServer() {
        isRunning = true
        statusText.text = "Server running on localhost:$REDIRECT_PORT\n\nKeep this open!\n\nSSH tunnel setup:\nFrom VPS: ssh -R 1455:localhost:1455 user@phone-ip"

        serverThread = Thread {
            try {
                serverSocket = ServerSocket(REDIRECT_PORT)
                while (isRunning) {
                    try {
                        val client = serverSocket?.accept()
                        client?.use { c ->
                            val reader = c.getInputStream().bufferedReader()
                            val request = reader.readText()

                            val codeMatch = Regex("code=([^&\\s]+)").find(request)
                            val stateMatch = Regex("state=([^&\\s]+)").find(request)

                            val code = codeMatch?.groupValues?.get(1)
                            val state = stateMatch?.groupValues?.get(1)

                            if (code != null) {
                                runOnUiThread {
                                    statusText.text = "Code received!\n\nSending to OpenCode..."
                                }
                                forwardCodeToOpenCode(code, state)
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
                                <body style="font-family: system-ui, sans-serif; text-align: center; padding: 40px; background: #f5f5f5;">
                                    <div style="max-width: 400px; margin: 0 auto; background: white; padding: 30px; border-radius: 12px;">
                                        <h1 style="color: #10a37f;">✓ Authorization Complete</h1>
                                        <p>Code sent to OpenCode!</p>
                                        <p style="color: #666; font-size: 14px;">You can close this app now.</p>
                                    </div>
                                </body>
                                </html>
                            """.trimIndent()
                            c.getOutputStream().write(response.toByteArray())
                            c.getOutputStream().flush()
                        }
                    } catch (e: Exception) {
                        if (isRunning) {}
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Error: ${e.message}\n\nPort $REDIRECT_PORT may be in use."
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

    private fun forwardCodeToOpenCode(code: String, state: String?) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("http://localhost:1455/auth/callback?code=$code${state?.let { "&state=$it" } ?: ""}")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                val response = conn.inputStream.bufferedReader().readText()

                withContext(Dispatchers.Main) {
                    statusText.text = "✓ Code sent to OpenCode!\n\nOpenCode should now be authenticated."
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "Code received!\n\nError sending to OpenCode: ${e.message}\n\nMake sure SSH tunnel is active:\nssh -R 1455:localhost:1455 user@vps-ip"
                }
            }
        }
    }

    private fun startOAuth() {
        webView.visibility = android.view.View.VISIBLE
        statusText.text = "Starting server..."

        startLocalServer()

        val (codeChallenge, state) = generateOAuthParams()
        val redirectUri = "http://127.0.0.1:$REDIRECT_PORT/auth/callback"

        val oauthUrl = buildOAuthUrl(codeChallenge, state, redirectUri)

        statusText.text = "Opening ChatGPT..."
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

    override fun onDestroy() {
        super.onDestroy()
        stopLocalServer()
    }
}
