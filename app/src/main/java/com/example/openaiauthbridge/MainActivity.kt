package com.example.openaiauthbridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
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

        statusText.text = "Tap to load ChatGPT\n\n(Login if needed, then tap again)"
        statusText.setOnClickListener {
            testSession(it)
        }
    }

    private fun setupWebView() {
        val webSettings: WebSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.databaseEnabled = true
        webSettings.setSupportMultipleWindows(false)
        webSettings.javaScriptCanOpenWindowsAutomatically = false

        // CRITICAL: Enable third-party cookies (evidence-based)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        }
        CookieManager.getInstance().setAcceptCookie(true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // Clear cookies cache to ensure fresh data
                WebStorage.getInstance().deleteAllData()
            }
        }
    }

    fun loadChatGPT(view: android.view.View) {
        statusText.text = "Loading ChatGPT..."
        webView.visibility = android.view.View.VISIBLE
        webView.loadUrl("https://chat.openai.com")
    }

    fun testSession(view: android.view.View) {
        statusText.text = "Checking session..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // First, ensure we're logged in by loading the page
                withContext(Dispatchers.Main) {
                    webView.loadUrl("https://chat.openai.com")
                }

                // Wait for page to load
                kotlinx.coroutines.delay(3000)

                // Now get cookies - this should work per Android docs
                val cookieManager = CookieManager.getInstance()
                val cookies = cookieManager.getCookie("https://chat.openai.com")
                val allCookies = cookieManager.getCookie("https://chatgpt.com")

                android.util.Log.d("COOKIES", "chat.openai.com: $cookies")
                android.util.Log.d("COOKIES", "chatgpt.com: $allCookies")

                withContext(Dispatchers.Main) {
                    if (!cookies.isNullOrEmpty() || !allCookies.isNullOrEmpty()) {
                        // We have cookies, test them
                        testCookiesWithAPI(cookies ?: allCookies)
                    } else {
                        statusText.text = """
                            No cookies found.

                            ── Troubleshooting ──

                            1. Are you logged into chat.openai.com?
                            2. Check if third-party cookies are blocked
                            3. Try logging in again in the WebView

                            Tap to retry
                        """.trimIndent()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "Error: ${e.message}"
                }
            }
        }
    }

    private fun testCookiesWithAPI(cookies: String) {
        statusText.text = "Testing cookies with ChatGPT API..."

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(API_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Cookie", cookies)
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                conn.setRequestProperty("Accept", "application/json")
                conn.connectTimeout = 30000
                conn.readTimeout = 30000

                val responseCode = conn.responseCode
                val response = if (responseCode >= 400) {
                    conn.errorStream?.bufferedReader()?.readText() ?: ""
                } else {
                    conn.inputStream.bufferedReader().readText()
                }

                android.util.Log.d("API_TEST", "Response code: $responseCode")
                android.util.Log.d("API_TEST", "Response: ${response.take(500)}")

                withContext(Dispatchers.Main) {
                    parseAPIResponse(responseCode, response, cookies)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "API test failed: ${e.message}\n\nTap to retry"
                }
            }
        }
    }

    private fun parseAPIResponse(code: Int, response: String, cookies: String) {
        when {
            code == 200 && response.contains("data") -> {
                // Success! We have a working session
                showSuccess(cookies)
            }
            code == 401 || code == 403 -> {
                statusText.text = """
                    Cookies found but not authenticated.

                    Response: ${response.take(200)}

                    Please login to ChatGPT in the WebView first, then try again.
                """.trimIndent()
            }
            code == 429 -> {
                statusText.text = """
                    Rate limited.

                    Response: ${response.take(200)}

                    Wait a moment and try again.
                """.trimIndent()
            }
            else -> {
                statusText.text = """
                    Unexpected response:

                    Code: $code
                    Response: ${response.take(300)}

                    Tap to retry
                """.trimIndent()
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

            API responded successfully.

            ── TAP TO COPY ──

            1st tap: All cookies
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
            val clip = ClipData.newPlainText("OpenAI", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Copy failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
