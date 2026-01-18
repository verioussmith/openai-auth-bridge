package com.example.openaiauthbridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var vpsUrlInput: EditText
    private lateinit var phoneIpText: TextView
    private lateinit var statusText: TextView
    private lateinit var configureButton: Button
    private lateinit var helpButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("AuthBridgePrefs", Context.MODE_PRIVATE)

        vpsUrlInput = findViewById(R.id.vpsUrlInput)
        phoneIpText = findViewById(R.id.phoneIpText)
        statusText = findViewById(R.id.statusText)
        configureButton = findViewById(R.id.configureButton)
        helpButton = findViewById(R.id.helpButton)

        vpsUrlInput.setText(prefs.getString("vps_url", ""))
        phoneIpText.text = "Phone IP: ${getLocalIpAddress()}"

        configureButton.setOnClickListener {
            configureOpenCode()
        }

        helpButton.setOnClickListener {
            showHelp()
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
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
            return "Error: ${e.message}"
        }
        return "Unknown"
    }

    private fun configureOpenCode() {
        val vpsUrl = vpsUrlInput.text.toString().trim()
        if (vpsUrl.isEmpty()) {
            statusText.text = "Enter CloudFlare URL first"
            return
        }
        
        prefs.edit().putString("vps_url", vpsUrl).apply()
        
        configureButton.isEnabled = false
        statusText.text = "Configuring..."
        
        val configUrl = "$vpsUrl/opencode/configure?url=${java.net.URLEncoder.encode(vpsUrl, "UTF-8")}"
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = URL(configUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                
                val response = conn.inputStream.bufferedReader().readText()
                
                withContext(Dispatchers.Main) {
                    if (response.contains("success")) {
                        statusText.text = "✅ Done!\n\nOpenCode is configured."
                    } else {
                        statusText.text = "❌ Failed\n\n$response"
                    }
                    configureButton.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "❌ Error: ${e.message}"
                    configureButton.isEnabled = true
                }
            }
        }
    }

    private fun showHelp() {
        statusText.text = "📱 SETUP:\n\n1. Install Termux from Play Store\n2. In Termux, run:\n   pkg install cloudflared\n   cloudflared tunnel --url http://localhost:1455\n3. Copy the HTTPS URL (like https://xyz.trycloudflare.com)\n4. Paste it above\n5. Tap Configure"
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up shared prefs on uninstall (user would need to clear app data)
        // No files written outside app's private storage
    }
}
