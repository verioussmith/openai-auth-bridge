package com.example.openaiauthbridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Base64
import android.webkit.CookieManager
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
        webSettings.savePassword = true
        webSettings.saveFormData = true

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (url.startsWith("http://localhost:1455") && url.contains("code=")) {
                    handleCallback(url)
                    return true
                }
                return false
            }
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
        } catch (e: Exception) {}
        return "localhost"
    }

    private fun startOAuth() {
        statusText.text = "Starting OAuth server..."
        webView.visibility = android.view.View.VISIBLE

        startLocalServer()

        val (verifier, challenge) = generatePKCE()
        codeVerifier = verifier
        authState = generateState()

        val oauthUrl = buildOAuthUrl(challenge, authState!!)

        statusText.text = "Opening ChatGPT login..."
        webView.loadUrl(oauthUrl)
    }

    private fun startLocalServer() {
        isRunning.set(true)

        serverThread = Thread {
            try {
                serverSocket = ServerSocket(1455, 0, InetAddress.getByName("localhost"))

                while (isRunning.get()) {
                    try {
                        val client = serverSocket?.accept()
                        client?.use { c ->
                            val reader = BufferedReader(InputStreamReader(c.getInputStream()))
                            val request = reader.readText()

                            if (request.contains("code=")) {
                                val codeMatch = Regex("code=([^&\\s]+)").find(request)
                                val stateMatch = Regex("state=([^&\\s]+)").find(request)

                                authCode = codeMatch?.groupValues?.get(1)
                                authState = stateMatch?.groupValues?.get(1)

                                runOnUiThread {
                                    if (authCode != null) {
                                        statusText.text = "Authorization code received!\n\nExchanging for tokens..."
                                    }
                                }

                                exchangeCodeForTokens()
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
                                    <div style="max-width: 500px; margin: 0 auto; background: white; padding: 30px; border-radius: 12px;">
                                        <h1 style="color: #10a37f;">Authorization Complete</h1>
                                        <p>You can close this page.</p>
                                    </div>
                                </body>
                                </html>
                            """.trimIndent()

                            val output: OutputStream = c.getOutputStream()
                            output.write(response.toByteArray())
                            output.flush()
                        }
                    } catch (e: Exception) {
                        if (isRunning.get()) {}
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Error: ${e.message}\n\nMake sure no other app is using port 1455"
                }
            }
        }
        serverThread?.start()
    }

    private fun handleCallback(url: String) {
        try {
            val parsedUrl = URL(url)
            val code = parsedUrl.query?.let { query ->
                Regex("code=([^&]+)").find(query)?.groupValues?.get(1)
            }

            if (code != null) {
                authCode = code
                statusText.text = "Authorization code received!\n\nExchanging for tokens..."
                exchangeCodeForTokens()
            }
        } catch (e: Exception) {
            statusText.text = "Error: ${e.message}"
        }
    }

    private fun exchangeCodeForTokens() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = "grant_type=authorization_code&client_id=$CLIENT_ID&code=$authCode&code_verifier=$codeVerifier&redirect_uri=${java.net.URLEncoder.encode(REDIRECT_URI, "UTF-8")}"

                val url = URL(TOKEN_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.doOutput = true
                conn.connectTimeout = 30000
                conn.readTimeout = 30000

                conn.outputStream.write(body.toByteArray())
                conn.outputStream.flush()

                val response = conn.inputStream.bufferedReader().readText()

                withContext(Dispatchers.Main) {
                    parseTokenResponse(response)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "Token exchange failed: ${e.message}\n\nCode: $authCode\n\nCopy code to OpenCode manually."
                }
            }
        }
    }

    private fun parseTokenResponse(response: String) {
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
                    showManualCode()
                }
            } else {
                showManualCode()
            }
        } catch (e: Exception) {
            showManualCode()
        }
    }

    private fun showTokens(accessToken: String, refreshToken: String, expiresIn: Int) {
        val expiresAt = System.currentTimeMillis() + (expiresIn * 1000)

        statusText.text = """
            ✓ Authentication Complete!

            Access Token:
            $accessToken

            Refresh Token:
            $refreshToken

            Expires: ${java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(expiresAt))}

            Tap here to copy access token
        """.trimIndent()

        statusText.setOnClickListener {
            copyToClipboard(accessToken)
        }

        stopLocalServer()
    }

    private fun showManualCode() {
        statusText.text = """
            Authorization Code: $authCode

            Tap here to copy, then paste into OpenCode:
            opencode auth login

            Or paste this URL:
            http://localhost:1455/auth/callback?code=$authCode${authState?.let { "&state=$it" } ?: ""}
        """.trimIndent()

        statusText.setOnClickListener {
            copyToClipboard(authCode ?: "")
        }

        stopLocalServer()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("OpenAI Auth", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show()
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
