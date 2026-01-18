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
import java.net.NetworkInterface
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
    private val REDIRECT_URI = "http://localhost:1455/auth/callback"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        webView = findViewById(R.id.webView)

        setupWebView()

        statusText.text = "Tap to start OAuth"
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
                android.util.Log.d("OAuth", "Loading URL: $url")

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
                            android.util.Log.d("OAuth", "Code captured: ${code.take(20)}...")
                            runOnUiThread {
                                statusText.text = "Code received!\n\nExchanging for tokens..."
                            }
                            exchangeCodeForTokens()
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("OAuth", "Error parsing callback", e)
                        runOnUiThread {
                            statusText.text = "Error: ${e.message}"
                        }
                    }
                    return true
                }
                return false
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

            android.util.Log.d("OAuth", "Starting server on port 1455")
            startLocalServer()

            val oauthUrl = buildOAuthUrl(challenge, authState!!)
            android.util.Log.d("OAuth", "Opening OAuth URL: ${oauthUrl.take(100)}...")

            statusText.text = "Opening ChatGPT login..."
            webView.loadUrl(oauthUrl)
        } catch (e: Exception) {
            android.util.Log.e("OAuth", "Error starting OAuth", e)
            statusText.text = "Error: ${e.message}"
        }
    }

    private fun startLocalServer() {
        isRunning.set(true)

        serverThread = Thread {
            try {
                serverSocket = ServerSocket(1455, 0, InetAddress.getByName("localhost"))
                android.util.Log.d("OAuth", "Server started on port 1455")

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

                        android.util.Log.d("OAuth", "Server received request: ${request.take(100)}...")

                        if (request.contains("code=") && authCode == null) {
                            try {
                                val codeMatch = Regex("code=([^&\\s]+)").find(request)
                                val stateMatch = Regex("state=([^&\\s]+)").find(request)

                                authCode = codeMatch?.groupValues?.get(1)
                                authState = stateMatch?.groupValues?.get(1)

                                android.util.Log.d("OAuth", "Server captured code: ${authCode?.take(20)}...")

                                runOnUiThread {
                                    statusText.text = "Code received!\n\nExchanging for tokens..."
                                }

                                exchangeCodeForTokens()
                            } catch (e: Exception) {
                                android.util.Log.e("OAuth", "Error parsing code", e)
                            }
                        }

                        val output: OutputStream = client.getOutputStream()
                        output.write(responseHtml.toByteArray())
                        output.flush()
                        client.close()
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            android.util.Log.e("OAuth", "Server error", e)
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("OAuth", "Failed to start server", e)
                runOnUiThread {
                    statusText.text = "Error starting server: ${e.message}\n\nMake sure port 1455 is free"
                }
            }
        }
        serverThread?.start()
    }

    private fun exchangeCodeForTokens() {
        val code = authCode ?: return
        val verifier = codeVerifier ?: return

        android.util.Log.d("OAuth", "Exchanging code for tokens...")
        android.util.Log.d("OAuth", "Code: ${code.take(20)}...")
        android.util.Log.d("OAuth", "Verifier: ${verifier.take(20)}...")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = "grant_type=authorization_code&client_id=$CLIENT_ID&code=$code&code_verifier=$verifier&redirect_uri=${java.net.URLEncoder.encode(REDIRECT_URI, "UTF-8")}"

                android.util.Log.d("OAuth", "POST to $TOKEN_URL")

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
                android.util.Log.d("OAuth", "Response code: $responseCode")

                val response = if (responseCode >= 400) {
                    conn.errorStream?.bufferedReader()?.readText() ?: ""
                } else {
                    conn.inputStream.bufferedReader().readText()
                }

                android.util.Log.d("OAuth", "Response: $response")

                withContext(Dispatchers.Main) {
                    parseTokenResponse(response, responseCode)
                }
            } catch (e: Exception) {
                android.util.Log.e("OAuth", "Token exchange failed", e)
                withContext(Dispatchers.Main) {
                    statusText.text = "Network error: ${e.message}\n\nTap to retry"
                    statusText.setOnClickListener {
                        startOAuth()
                    }
                }
            }
        }
    }

    private fun parseTokenResponse(response: String, statusCode: Int = 200) {
        android.util.Log.d("OAuth", "Parsing token response, status: $statusCode")

        if (statusCode >= 400) {
            android.util.Log.e("OAuth", "Error response from OpenAI: $response")
            showManualCode("OpenAI Error: ${response.take(200)}")
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
                    android.util.Log.d("OAuth", "Successfully parsed tokens")
                    showTokens(accessToken, refreshToken, expiresIn)
                } else {
                    android.util.Log.e("OAuth", "Missing tokens in response")
                    showManualCode("Could not parse tokens from response")
                }
            } else {
                android.util.Log.e("OAuth", "No tokens in response: $response")
                showManualCode("No tokens received")
            }
        } catch (e: Exception) {
            android.util.Log.e("OAuth", "Parse error", e)
            showManualCode("Parse error: ${e.message}")
        }
    }

    private fun showTokens(accessToken: String, refreshToken: String, expiresIn: Int) {
        val expiresAt = System.currentTimeMillis() + (expiresIn * 1000)
        val expiresDisplay = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(expiresAt))

        statusText.text = """
            ✓ Authentication Complete!

            Access Token:
            $accessToken

            Refresh Token:
            $refreshToken

            Expires: $expiresDisplay

            Tap here to copy access token
        """.trimIndent()

        statusText.setOnClickListener {
            copyToClipboard(accessToken)
        }

        stopLocalServer()
    }

    private fun showManualCode(error: String? = null) {
        val codeDisplay = authCode ?: "unknown"
        val msg = if (error != null) "$error\n\n" else ""

        statusText.text = """
            $msg
            Authorization Code: $codeDisplay

            Tap here to copy code, then on VPS run:
            export OPENAI_SESSION="$codeDisplay"
            opencode auth login

            Or restart this app to try again.
        """.trimIndent()

        statusText.setOnClickListener {
            copyToClipboard(codeDisplay)
        }

        stopLocalServer()
    }

    private fun copyToClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("OpenAI", text)
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

    private fun buildOAuthUrl(codeChallenge: String, state: String): String {
        return buildString {
            append(AUTHORIZE_URL)
            append("?response_type=code")
            append("&client_id=$CLIENT_ID")
            append("&redirect_uri=${java.net.URLEncoder.encode(REDIRECT_URI, "UTF-8")}")
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
