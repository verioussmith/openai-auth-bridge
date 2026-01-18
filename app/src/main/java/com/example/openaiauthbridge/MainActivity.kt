package com.example.openaiauthbridge

import android.os.Bundle
import android.webkit.CookieManager
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

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var webView: WebView

    private val API_URL = "https://api.openai.com/v1"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        webView = findViewById(R.id.webView)

        setupWebView()

        statusText.text = "Tap to open ChatGPT"
        statusText.setOnClickListener {
            startLogin()
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
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (url.contains("chat.openai.com")) {
                    statusText.text = "ChatGPT loaded\n\nTap here to extract session"
                }
            }
        }
    }

    private fun startLogin() {
        statusText.text = "Loading ChatGPT..."
        webView.visibility = android.view.View.VISIBLE
        webView.loadUrl("https://chat.openai.com")
    }

    fun extractSession(view: android.view.View) {
        statusText.text = "Extracting session..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cookies = CookieManager.getInstance().getCookie("https://chat.openai.com")

                if (cookies.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        statusText.text = "No cookies found.\n\nPlease log into ChatGPT first."
                    }
                    return@launch
                }

                val sessionToken = extractSessionToken(cookies)

                if (sessionToken == null) {
                    withContext(Dispatchers.Main) {
                        statusText.text = "Session token not found in cookies.\n\nCookies: ${cookies.take(100)}..."
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    statusText.text = """
                        Session extracted!

                        Token: ${sessionToken.take(50)}...

                        On VPS, run:
                        export OPENAI_SESSION="$sessionToken"

                        Then: opencode login --session
                    """.trimIndent()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "Error: ${e.message}"
                }
            }
        }
    }

    private fun extractSessionToken(cookies: String): String? {
        val cookiePairs = cookies.split(";")
        for (pair in cookiePairs) {
            val trimmed = pair.trim()
            if (trimmed.startsWith("__cf_bm=") ||
                trimmed.startsWith("session_user") ||
                trimmed.startsWith("session_token") ||
                trimmed.startsWith("auth_token")) {
                val parts = trimmed.split("=", limit = 2)
                if (parts.size == 2) {
                    return parts[1]
                }
            }
        }
        return cookies.split(";").firstOrNull { it.trim().contains("=") }?.split("=")?.get(1)
    }
}
