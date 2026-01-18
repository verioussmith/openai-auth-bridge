package com.example.openaiauthbridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Base64
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var webView: WebView

    private var serverThread: Thread? = null
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)

    private var authCode: String? = null
    private var authState: String? = null
    private var codeVerifier: String? = null

    private val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    private val AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize"
    private val TOKEN_URL = "https://auth.openai.com/oauth/token"
    private var currentPort = 1455
    private var serverPort = 1455

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        webView = findViewById(R.id.webView)

        setupWebView()

        statusText.text = "Tap to start OAuth\n\n(Make sure you're logged into chatgpt.com first)"
        statusText.setOnClickListener {
            startOAuth()
        }
    }

    private fun setupWebView() {
        val webSettings: WebSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.allowFileAccess = true

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (url.startsWith("http://localhost:1455") && url.contains("code=")) {
                    try {
                        val parsedUrl = URL(url)
                        val code = parsedUrl.query?.let { query ->
                            Regex("code=([^&]+)").find(query)?.groupValues?.get(1)
                        }
                        val state = parsedUrl.query?.let { query ->
                            Regex("state=([^&]+)").find(query)?.groupValues?.get(1)
                        }

                        if (code != null) {
                            authCode = code
                            authState = state
                            runOnUiThread {
                                statusText.text = "Code received!\n\nExchanging for tokens..."
                            }
                            exchangeCodeForTokens()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            statusText.text = "Error: ${e.message}"
                        }
                    }
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (url.contains("chat.openai.com") && !url.contains("auth")) {
                    runOnUiThread {
                        statusText.text = "ChatGPT loaded\n\nTap to start OAuth"
                    }
                }
            }
        }
    }

    private fun startOAuth() {
        try {
            statusText.text = "Starting OAuth server..."
            webView.visibility = android.view.View.VISIBLE

            val (verifier, challenge) = generatePKCE()
            codeVerifier = verifier
            authState = generateState()

            val port = startLocalServer()
            if (port == null) {
                return
            }
            currentPort = port

            val oauthUrl = buildOAuthUrl(challenge, authState!!, port)

            statusText.text = "Opening ChatGPT login..."
            webView.loadUrl(oauthUrl)
        } catch (e: Exception) {
            statusText.text = "Error: ${e.message}"
        }
    }

    private fun startLocalServer(): Int? {
        isRunning.set(true)
        var usedPort: Int? = null

        serverThread = Thread {
            var attempts = 0
            val maxAttempts = 5
            var port = 1455

            while (isRunning.get() && attempts < maxAttempts) {
                try {
                    serverSocket = ServerSocket(port, 0, InetAddress.getByName("localhost"))
                    usedPort = port
                    android.util.Log.d("OAuth", "Server started on port $port")

                    val responseHtml = """
                        HTTP/1.1 200 OK
                        Content-Type: text/html; charset=utf-8
                        Connection: close

                        <!DOCTYPE html>
                        <html>
                        <head>
                            <meta charset="UTF-8">
                            <meta name="viewport" content="width=device-width, initial-scale=1">
                            <title>Authorization Complete</title>
                        </head>
                        <body style="font-family: system-ui, sans-serif; text-align: center; padding: 40px; background: #f5f5f5;">
                            <div style="max-width: 500px; margin: 0 auto; background: white; padding: 30px; border-radius: 12px;">
                                <h1 style="color: #10a37f;">Authorization Complete</h1>
                                <p>You can close this page.</p>
                            </div>
                        </body>
                        </html>
                    """.trimIndent()

                    while (isRunning.get()) {
                        try {
                            val client = serverSocket?.accept() ?: break
                            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                            val request = reader.readText()

                            if (request.contains("code=") && authCode == null) {
                                val codeMatch = Regex("code=([^&\\s]+)").find(request)
                                val stateMatch = Regex("state=([^&\\s]+)").find(request)

                                authCode = codeMatch?.groupValues?.get(1)
                                authState = stateMatch?.groupValues?.get(1)

                                runOnUiThread {
                                    statusText.text = "Code received!\n\nExchanging for tokens..."
                                }

                                exchangeCodeForTokens()
                            }

                            val output: OutputStream = client.getOutputStream()
                            output.write(responseHtml.toByteArray())
                            output.flush()
                            client.close()
                        } catch (e: Exception) {
                            if (isRunning.get()) {}
                        }
                    }
                    break
                } catch (e: Exception) {
                    attempts++
                    port = 1455 + attempts
                    android.util.Log.d("OAuth", "Port $port failed, trying $port...")
                    if (attempts >= maxAttempts) {
                        runOnUiThread {
                            statusText.text = "Error: All ports in use (1455-1459)\n\nClose other apps and try again"
                        }
                    }
                }
            }
        }
        serverThread?.start()

        // Wait briefly for server to start
        Thread.sleep(500)
        return usedPort
    }

    private fun exchangeCodeForTokens() {
        val code = authCode ?: return
        val verifier = codeVerifier ?: return
        val redirectUri = "http://localhost:$currentPort/auth/callback"

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = "grant_type=authorization_code&client_id=$CLIENT_ID&code=$code&code_verifier=$verifier&redirect_uri=${java.net.URLEncoder.encode(redirectUri, "UTF-8")}"

                val url = URL(TOKEN_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.doOutput = true
                conn.connectTimeout = 30000
                conn.readTimeout = 30000

                conn.outputStream.write(body.toByteArray())
                conn.outputStream.flush()

                val responseCode = conn.responseCode
                val response = if (responseCode >= 400) {
                    conn.errorStream?.bufferedReader()?.readText() ?: ""
                } else {
                    conn.inputStream.bufferedReader().readText()
                }

                withContext(Dispatchers.Main) {
                    parseTokenResponse(response, responseCode)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "Network error: ${e.message}\n\nTap to retry"
                    statusText.setOnClickListener {
                        startOAuth()
                    }
                }
            }
        }
    }

    private fun parseTokenResponse(response: String, statusCode: Int) {
        if (statusCode >= 400) {
            statusText.text = """
                OpenAI Error:

                ${response.take(500)}

                ── Troubleshooting ──

                1. Make sure you're logged into chatgpt.com first
                2. Try logging out and back in on the WebView
                3. Tap here to retry
            """.trimIndent()

            statusText.setOnClickListener {
                startOAuth()
            }
            return
        }

        try {
            if (response.contains("access_token") && response.contains("refresh_token")) {
                val accessMatch = Regex("\"access_token\":\"([^\"]+)\"").find(response)
                val refreshMatch = Regex("\"refresh_token\":\"([^\"]+)\"").find(response)
                val expiresMatch = Regex("\"expires_in\":(\\d+)").find(response)

                val accessToken = accessMatch?.groupValues?.get(1)
                val refreshToken = refreshMatch?.groupValues?.get(1)
                val expiresIn = expiresMatch?.groupValues?.get(1)?.toIntOrNull() ?: 3600

                if (accessToken != null && refreshToken != null) {
                    showTokens(accessToken, refreshToken, expiresIn)
                } else {
                    statusText.text = "Partial token response:\n\n$response\n\nTap to retry"
                    statusText.setOnClickListener {
                        startOAuth()
                    }
                }
            } else {
                statusText.text = """
                    No tokens in response:

                    $response

                    Tap here to retry
                """.trimIndent()

                statusText.setOnClickListener {
                    startOAuth()
                }
            }
        } catch (e: Exception) {
            statusText.text = "Parse error: ${e.message}\n\nResponse:\n${response.take(200)}"
            statusText.setOnClickListener {
                startOAuth()
            }
        }
    }

    private fun showTokens(accessToken: String, refreshToken: String, expiresIn: Int) {
        val expiresAt = System.currentTimeMillis() + (expiresIn * 1000)
        val expiresDisplay = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(expiresAt))

        var tapCount = 0
        var showingAccess = true

        statusText.text = """
            ✓ Authentication Complete!

            ── TAP TO COPY ──

            1st tap: Access Token
            2nd tap: Refresh Token

            Access: ${accessToken.take(30)}...
            Refresh: ${refreshToken.take(30)}...

            Expires: $expiresDisplay
        """.trimIndent()

        statusText.setOnClickListener {
            tapCount++
            if (tapCount % 2 == 1) {
                showingAccess = true
                copyToClipboard(accessToken)
                statusText.text = """
                    Copied ACCESS TOKEN

                    Tap again for REFRESH TOKEN

                    Access: ${accessToken.take(30)}...
                    Refresh: ${refreshToken.take(30)}...
                """.trimIndent()
            } else {
                showingAccess = false
                copyToClipboard(refreshToken)
                statusText.text = """
                    Copied REFRESH TOKEN

                    Tap again for ACCESS TOKEN

                    Access: ${accessToken.take(30)}...
                    Refresh: ${refreshToken.take(30)}...
                """.trimIndent()
            }
        }

        stopLocalServer()
    }

    private fun copyToClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("OpenAI Token", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Copy failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopLocalServer() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (e: Exception) {}
        serverThread?.interrupt()
    }

    private fun generatePKCE(): Pair<String, String> {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val random = java.security.SecureRandom()
        val codeVerifier = (1..128).map { chars[random.nextInt(chars.length)] }.joinToString("")

        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(codeVerifier.toByteArray())
        val codeChallenge = Base64.encodeToString(hash, Base64.NO_WRAP or Base64.URL_SAFE).replace("=", "")

        return Pair(codeVerifier, codeChallenge)
    }

    private fun generateState(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = java.security.SecureRandom()
        return (1..32).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    private fun buildOAuthUrl(codeChallenge: String, state: String, port: Int = 1455): String {
        val redirectUri = "http://localhost:$port/auth/callback"
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
