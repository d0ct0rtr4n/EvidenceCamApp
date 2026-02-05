package com.evidencecam.app.ui.main

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.evidencecam.app.databinding.ActivityPrivacyPolicyBinding

class PrivacyPolicyActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityPrivacyPolicyBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrivacyPolicyBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Privacy Policy"
        
        binding.webView.loadDataWithBaseURL(null, getPrivacyPolicyHtml(), "text/html", "UTF-8", null)
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
    
    private fun getPrivacyPolicyHtml() = """
        <!DOCTYPE html>
        <html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <style>
            body { font-family: sans-serif; padding: 16px; line-height: 1.6; color: #333; }
            h1 { font-size: 24px; } h2 { font-size: 18px; margin-top: 24px; }
            p { margin: 12px 0; } ul { padding-left: 20px; } li { margin: 8px 0; }
        </style>
        </head><body>
        <h1>Privacy Policy</h1>
        <p>Last updated: January 2025</p>
        <h2>1. Information We Collect</h2>
        <ul>
            <li><strong>Video/Audio:</strong> Recorded using your device's camera and microphone.</li>
            <li><strong>Storage:</strong> We monitor available storage to manage recording files.</li>
        </ul>
        <h2>2. How We Use Your Information</h2>
        <p>Recordings are stored locally and optionally uploaded to your configured cloud service.</p>
        <h2>3. Your Rights</h2>
        <p>You can delete recordings at any time and disconnect cloud services in settings.</p>
        <h2>4. Legal Compliance</h2>
        <p><strong>Important:</strong> You are responsible for complying with local recording laws.</p>
        </body></html>
    """.trimIndent()
}
