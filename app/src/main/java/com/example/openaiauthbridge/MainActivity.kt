package com.example.openaiauthbridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import android.util.Base64
import android.webkit.WebSettings
import android.webkit.JavascriptInterface

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var webView: WebView

    private var vpsUrl: String = ""
    private var receivedCode: String? = null

    private val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    private val AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize"

    inner class OAuthCallback {
        @JavascriptInterface
        fun onCodeReceived(code: String, state: String) {
            receivedCode = code
            runOnUiThread {
                statusText.text = "Sending code..."
            }
            sendCodeToVps()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        webView = findViewById(R.id.webView)

        setupWebView()

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
        webView.addJavascriptInterface(OAuthCallback(), "Android")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                view.loadUrl("""
                    javascript:
                    (function() {
                        var urlParams = new URLSearchParams(window.location.search);
                        var code = urlParams.get('code');
                        var state = urlParams.get('state');
                        if (code && window.Android) {
                            window.Android.onCodeReceived(code, state || '');
                        }
                    })();
                """.trimIndent())
            }
        }
    }

    private fun startOAuth() {
        if (vpsUrl.isEmpty()) {
            statusText.text = "Tap to start"
            statusText.setOnClickListener {
                vpsUrl = ""
                webView.visibility = android.view.View.VISIBLE
                startOAuth()
            }
            return
        }

        statusText.text = "Opening ChatGPT..."
        webView.visibility = android.view.View.VISIBLE

        val (codeVerifier, codeChallenge) = generatePKCE()
        val state = generateState()
        val redirectUri = "https://auth.openai.com/oauth/callback"

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
                    statusText.text = "Error: ${e.message}"
                }
            }
        }
    }
}
