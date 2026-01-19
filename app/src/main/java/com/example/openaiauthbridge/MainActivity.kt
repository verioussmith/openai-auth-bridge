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

    private val API_URL = "https://chatgpt.com/backend-api/models"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        webView = findViewById(R.id.webView)

        setupWebView()

        statusText.text = "Tap to open ChatGPT\n\nLogin, then tap again to test session"
        statusText.setOnClickListener {
            testSession(it)
        }
    }

    private fun setupWebView() {
        val webSettings: WebSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.allowFileAccess = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (url.contains("chat.openai.com")) {
                    runOnUiThread {
                        statusText.text = "ChatGPT loaded!\n\nTap to test session"
                    }
                }
            }
        }
    }

    fun loadChatGPT(view: android.view.View) {
        statusText.text = "Loading ChatGPT..."
        webView.visibility = android.view.View.VISIBLE
        webView.loadUrl("https://chat.openai.com")
    }

    fun testSession(view: android.view.View) {
        statusText.text = "Testing session..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Get cookies from WebView
                val cookies = CookieManager.getInstance().getCookie("https://chat.openai.com")

                if (cookies.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        statusText.text = "No cookies found.\n\nPlease login to ChatGPT first."
                    }
                    return@launch
                }

                // Test with API call
                val url = URL(API_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Cookie", cookies)
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.connectTimeout = 30000
                conn.readTimeout = 30000

                val responseCode = conn.responseCode
                val response = if (responseCode >= 400) {
                    conn.errorStream?.bufferedReader()?.readText() ?: ""
                } else {
                    conn.inputStream.bufferedReader().readText()
                }

                withContext(Dispatchers.Main) {
                    if (responseCode == 200 && response.contains("data")) {
                        // Success! We have a working session
                        showSuccess(cookies)
                    } else if (responseCode == 401 || responseCode == 403) {
                        statusText.text = """
                            Session not authenticated.

                            Response: ${response.take(200)}

                            Please login to ChatGPT first, then try again.
                        """.trimIndent()
                    } else {
                        statusText.text = """
                            Unexpected response:

                            Code: $responseCode
                            Response: ${response.take(300)}

                            Tap to retry
                        """.trimIndent()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "Error: ${e.message}\n\nTap to retry"
                }
            }
        }
    }

    private fun showSuccess(cookies: String) {
        // Extract useful cookies
        val cookiePairs = cookies.split(";")
        var sessionToken: String? = null
        var sessionUser: String? = null

        for (pair in cookiePairs) {
            val trimmed = pair.trim()
            if (trimmed.startsWith("session_token=")) {
                sessionToken = trimmed.substringAfter("=").substringBefore(",")
            }
            if (trimmed.startsWith("session_user=")) {
                sessionUser = trimmed.substringAfter("=").substringBefore(",")
            }
        }

        statusText.text = """
            ✓ Working Session!

            ── TAP TO COPY COOKIES ──

            1st tap: Full cookie string
            2nd tap: session_token
            3rd tap: session_user

            ${if (sessionToken != null) "session_token found ✓" else "session_token not found"}
            ${if (sessionUser != null) "session_user found ✓" else "session_user not found"}
        """.trimIndent()

        var tapCount = 0
        statusText.setOnClickListener {
            tapCount++
            when (tapCount % 3) {
                1 -> copyToClipboard(cookies)
                2 -> copyToClipboard(sessionToken ?: "not found")
                0 -> copyToClipboard(sessionUser ?: "not found")
            }
        }

        webView.visibility = android.view.View.GONE
    }

    private fun copyToClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("OpenAI Cookies", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Copy failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
