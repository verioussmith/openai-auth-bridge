package com.example.openaiauthbridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.webkit.WebStorage
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        webView = findViewById(R.id.webView)

        setupWebView()

        statusText.text = "Tap to open ChatGPT\n\n(Login normally, then tap again to extract session)"
        statusText.setOnClickListener {
            extractSession(it)
        }
    }

    private fun setupWebView() {
        val webSettings: WebSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.databaseEnabled = true
        webSettings.allowFileAccess = true
        webSettings.savePassword = true
        webSettings.saveFormData = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (url.contains("chat.openai.com")) {
                    runOnUiThread {
                        statusText.text = "ChatGPT loaded!\n\nIf logged in, tap to extract session.\n\nIf not logged in, login first then tap."
                    }
                }
            }
        }
    }

    fun loadChatGPT(view: android.view.View) {
        statusText.text = "Loading ChatGPT...\n\nPlease login if prompted"
        webView.visibility = android.view.View.VISIBLE
        webView.loadUrl("https://chat.openai.com")
    }

    fun extractSession(view: android.view.View) {
        statusText.text = "Extracting session..."

        // Try multiple methods to get authentication
        webView.evaluateJavascript("""
            (function() {
                var result = { localStorage: {}, sessionStorage: {}, cookies: document.cookie };
                
                // Get localStorage
                try {
                    for (var i = 0; i < localStorage.length; i++) {
                        var key = localStorage.key(i);
                        if (key.toLowerCase().includes('auth') || 
                            key.toLowerCase().includes('session') ||
                            key.toLowerCase().includes('token') ||
                            key.toLowerCase().includes('user')) {
                            result.localStorage[key] = localStorage.getItem(key);
                        }
                    }
                } catch(e) {}
                
                // Get sessionStorage
                try {
                    for (var i = 0; i < sessionStorage.length; i++) {
                        var key = sessionStorage.key(i);
                        if (key.toLowerCase().includes('auth') || 
                            key.toLowerCase().includes('session') ||
                            key.toLowerCase().includes('token') ||
                            key.toLowerCase().includes('user')) {
                            result.sessionStorage[key] = sessionStorage.getItem(key);
                        }
                    }
                } catch(e) {}
                
                return JSON.stringify(result);
            })();
        """) { value ->
            runOnUiThread {
                if (value == "{}" || value == "null") {
                    statusText.text = """
                        No session data found.

                        ── Troubleshooting ──

                        1. Are you logged into ChatGPT?
                        2. Try refreshing the page
                        3. Make sure you see your chat history

                        Tap to retry
                    """.trimIndent()
                } else {
                    showSessionData(value)
                }
            }
        }
    }

    private fun showSessionData(json: String) {
        try {
            val cleanJson = json.replace("\\\"", "\"").replace("\\\\", "\\")
            
            // Try to find useful tokens
            var accessToken: String? = null
            var refreshToken: String? = null
            var sessionToken: String? = null

            // Look for common patterns
            if (cleanJson.contains("access_token") || cleanJson.contains("accessToken")) {
                val pattern = "\"access[_-]?token[\"]?:[\"]?([^\"]+)".toRegex()
                accessToken = pattern.find(cleanJson)?.groupValues?.get(1)
            }
            
            if (cleanJson.contains("refresh_token") || cleanJson.contains("refreshToken")) {
                val pattern = "\"refresh[_-]?token[\"]?:[\"]?([^\"]+)".toRegex()
                refreshToken = pattern.find(cleanJson)?.groupValues?.get(1)
            }
            
            // Check cookies
            val cookiePattern = "cookies[\":][^\"]*session[_-]?token[^\"]*".toRegex()
            val cookieMatch = cookiePattern.find(cleanJson)
            if (cookieMatch != null) {
                sessionToken = cookieMatch.value.split("=").getOrNull(1)
            }

            statusText.text = """
                Session Data Found!

                ── TAP TO COPY ──

                1st tap: Access Token
                2nd tap: Refresh Token

                ${if (accessToken != null) "Access: ${accessToken.take(40)}..." else "Access: Not found"}
                ${if (refreshToken != null) "Refresh: ${refreshToken.take(40)}..." else "Refresh: Not found"}
                ${if (sessionToken != null) "Session: ${sessionToken.take(40)}..." else "Session: Not found"}
            """.trimIndent()

            var tapCount = 0
            statusText.setOnClickListener {
                tapCount++
                when (tapCount % 2) {
                    1 -> {
                        if (accessToken != null) {
                            copyToClipboard(accessToken)
                            statusText.text = "Copied ACCESS TOKEN!\n\nTap for REFRESH TOKEN"
                        } else {
                            statusText.text = "No access token found.\n\nTry logging out and back in."
                        }
                    }
                    0 -> {
                        if (refreshToken != null) {
                            copyToClipboard(refreshToken)
                            statusText.text = "Copied REFRESH TOKEN!\n\nTap for ACCESS TOKEN"
                        } else if (sessionToken != null) {
                            copyToClipboard(sessionToken)
                            statusText.text = "Copied SESSION TOKEN!\n\nTap for ACCESS TOKEN"
                        } else {
                            statusText.text = "No refresh token found."
                        }
                    }
                }
            }
        } catch (e: Exception) {
            statusText.text = "Error parsing: ${e.message}"
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("OpenAI Session", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Copy failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
