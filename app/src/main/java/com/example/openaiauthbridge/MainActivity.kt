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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var webView: WebView

    private val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    private val AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize"
    private val TOKEN_URL = "https://auth.openai.com/oauth/token"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        webView = findViewById(R.id.webView)

        setupWebView()

        statusText.text = "Tap to extract session cookies from ChatGPT"
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
                        statusText.text = "ChatGPT loaded!\n\nMake sure you're logged in.\n\nTap to extract cookies."
                    }
                }
            }
        }
    }

    private fun loadChatGPT() {
        statusText.text = "Loading ChatGPT..."
        webView.visibility = android.view.View.VISIBLE
        webView.loadUrl("https://chat.openai.com")
    }

    fun extractSession(view: android.view.View) {
        statusText.text = "Extracting session..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cookies = CookieManager.getInstance().getCookie("https://chat.openai.com")

                withContext(Dispatchers.Main) {
                    if (cookies.isNullOrEmpty()) {
                        statusText.text = "No cookies found.\n\nPlease log into ChatGPT first."
                    } else {
                        showCookies(cookies)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "Error: ${e.message}"
                }
            }
        }
    }

    private fun showCookies(cookies: String) {
        val cookiePairs = cookies.split(";")
        var sessionToken: String? = null
        var sessionUser: String? = null
        var cfBm: String? = null

        for (pair in cookiePairs) {
            val trimmed = pair.trim()
            when {
                trimmed.startsWith("session_token=") -> {
                    sessionToken = trimmed.substringAfter("=")
                }
                trimmed.startsWith("session_user=") -> {
                    sessionUser = trimmed.substringAfter("=")
                }
                trimmed.startsWith("__cf_bm=") -> {
                    cfBm = trimmed.substringAfter("=").substringBefore(",")
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
