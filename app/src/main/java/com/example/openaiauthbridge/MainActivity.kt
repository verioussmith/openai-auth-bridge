package com.example.openaiauthbridge

import android.content.Intent
import android.os.Bundle
import android.util.Base64
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
import java.net.URL
import java.security.MessageDigest

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var webView: WebView

    private var vpsUrl: String = ""
    private var receivedCode: String? = null

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

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }

    private fun handleIntent(intent: Intent) {
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

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (url.startsWith("http://") && url.contains(":1455") && url.contains("code=")) {
                    extractCodeFromUrl(url)
                    return true
                }
                return false
            }
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
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
            return "127.0.0.1"
        }
        return "127.0.0.1"
    }

    private fun extractCodeFromUrl(url: String) {
        try {
            val parsedUrl = URL(url)
            val code = parsedUrl.query?.let { query ->
                Regex("code=([^&]+)").find(query)?.groupValues?.get(1)
            }

            if (code != null) {
                receivedCode = code
                statusText.text = "Code received! Sending to VPS..."
                sendCodeToVps()
            } else {
                statusText.text = "No code in redirect URL"
            }
        } catch (e: Exception) {
            statusText.text = "Error: ${e.message}"
        }
    }

    private fun startOAuth() {
        statusText.text = "Opening ChatGPT..."
        webView.visibility = android.view.View.VISIBLE

        val phoneIp = getLocalIpAddress()
        val redirectUri = "http://$phoneIp:1455/auth/callback"

        val (codeVerifier, codeChallenge) = generatePKCE()
        val state = generateState()

        val oauthUrl = buildOAuthUrl(codeChallenge, state, redirectUri)
        webView.loadUrl(oauthUrl)
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
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "Error: ${e.message}\n\nCode: $receivedCode"
                }
            }
        }
    }
}
