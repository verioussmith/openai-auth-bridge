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

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var webView: WebView
    private var currentToken: String? = null

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
                        statusText.text = "Session token not found.\n\nTry logging out and back in."
                    }
                    return@launch
                }

                currentToken = sessionToken

                withContext(Dispatchers.Main) {
                    statusText.text = "Session extracted!\n\nTap here to copy to clipboard"

                    statusText.setOnClickListener {
                        copyToClipboard(sessionToken)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "Error: ${e.message}"
                }
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("OpenAI Session", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Copied to clipboard!", Toast.LENGTH_SHORT).show()

        statusText.text = "Copied!\n\nOn VPS run:\nexport OPENAI_SESSION=\"[token]\"\n\nThen: opencode login --session"
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
