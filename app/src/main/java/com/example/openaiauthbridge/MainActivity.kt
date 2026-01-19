package com.example.openaiauthbridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.webkit.CookieManager
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

        statusText.text = "Tap to load ChatGPT"
        statusText.setOnClickListener {
            loadChatGPT()
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
                    runOnUiThread {
                        statusText.text = "ChatGPT loaded!\n\nTap to extract cookies"
                    }
                }
            }
        }
    }

    private fun loadChatGPT() {
        statusText.text = "Loading ChatGPT...\n\nMake sure to log in!"
        webView.visibility = android.view.View.VISIBLE
        webView.loadUrl("https://chat.openai.com")
    }

    fun extractSession(view: android.view.View) {
        statusText.text = "Extracting cookies..."

        webView.evaluateJavascript("""
            (function() {
                var cookies = document.cookie.split(';');
                var result = {};
                for (var i = 0; i < cookies.length; i++) {
                    var cookie = cookies[i].trim();
                    if (cookie.indexOf('session_token') === 0) {
                        result.session_token = cookie.split('=')[1];
                    }
                    if (cookie.indexOf('session_user') === 0) {
                        result.session_user = cookie.split('=')[1];
                    }
                    if (cookie.indexOf('__cf_bm') === 0) {
                        result.cf_bm = cookie.split('=')[1];
                    }
                }
                return JSON.stringify(result);
            })();
        """) { cookiesJson ->
            runOnUiThread {
                if (cookiesJson == "{}" || cookiesJson == "null") {
                    statusText.text = """
                        No cookies found.

                        ── Troubleshooting ──

                        1. Are you logged into ChatGPT?
                        2. Try refreshing the page
                        3. Check if you see chat history

                        Tap to retry
                    """.trimIndent()
                } else {
                    showCookies(cookiesJson)
                }
            }
        }
    }

    private fun showCookies(cookiesJson: String) {
        try {
            val cleanJson = cookiesJson.replace("\"", "").replace("\\", "")
            val pairs = cleanJson.removePrefix("{").removeSuffix("}").split(",")
            
            var sessionToken: String? = null
            var sessionUser: String? = null
            var cfBm: String? = null

            for (pair in pairs) {
                val parts = pair.split(":")
                if (parts.size == 2) {
                    when (parts[0].trim()) {
                        "session_token" -> sessionToken = parts[1].trim()
                        "session_user" -> sessionUser = parts[1].trim()
                        "cf_bm" -> cfBm = parts[1].trim()
                    }
                }
            }

            statusText.text = """
                Session Extracted!

                ── TAP TO COPY ──

                1st tap: session_token
                2nd tap: session_user
                3rd tap: __cf_bm

                session_token: ${sessionToken?.take(40) ?: "Not found"}...
                session_user: ${sessionUser?.take(40) ?: "Not found"}...
                __cf_bm: ${cfBm?.take(40) ?: "Not found"}...
            """.trimIndent()

            var tapCount = 0
            statusText.setOnClickListener {
                tapCount++
                when (tapCount % 3) {
                    1 -> {
                        if (sessionToken != null) {
                            copyToClipboard(sessionToken)
                            statusText.text = "Copied session_token!\n\nTap for session_user"
                        }
                    }
                    2 -> {
                        if (sessionUser != null) {
                            copyToClipboard(sessionUser)
                            statusText.text = "Copied session_user!\n\nTap for __cf_bm"
                        }
                    }
                    0 -> {
                        if (cfBm != null) {
                            copyToClipboard(cfBm)
                            statusText.text = "Copied __cf_bm!\n\nTap for session_token"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            statusText.text = "Error parsing cookies: ${e.message}\n\nJSON: $cookiesJson"
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
