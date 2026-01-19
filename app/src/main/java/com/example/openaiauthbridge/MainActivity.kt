package com.example.openaiauthbridge

import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.security.MessageDigest
import android.util.Base64

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var webView: WebView

    private val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    private val AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize"
    private val REDIRECT_URI = "https://oauth.philoveracity.com/auth/callback"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        webView = findViewById(R.id.webView)

        setupWebView()

        statusText.text = "Tap to start OAuth\n\nRedirect: oauth.philoveracity.com"
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
                if (url.startsWith("https://oauth.philoveracity.com")) {
                    statusText.text = "OAuth callback received!\n\nCheck VPS for the authorization code."
                }
                return false
            }
        }
    }

    private fun startOAuth() {
        statusText.text = "Opening ChatGPT OAuth..."
        webView.visibility = android.view.View.VISIBLE

        val (codeChallenge, state) = generateOAuthParams()
        val oauthUrl = buildOAuthUrl(codeChallenge, state)

        webView.loadUrl(oauthUrl)
    }

    private fun generateOAuthParams(): Pair<String, String> {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val random = java.security.SecureRandom()
        val codeVerifier = (1..128).map { chars[random.nextInt(chars.length)] }.joinToString("")

        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(codeVerifier.toByteArray())
        val codeChallenge = Base64.encodeToString(hash, Base64.NO_WRAP or Base64.URL_SAFE).replace("=", "")

        val stateChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val state = (1..32).map { stateChars[random.nextInt(stateChars.length)] }.joinToString("")

        return Pair(codeChallenge, state)
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
}
