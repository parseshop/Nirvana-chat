package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

class ComposeSmsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        var address = ""
        val intentData = intent.data
        if (intentData != null) {
            val scheme = intentData.scheme
            if ("sms" == scheme || "smsto" == scheme) {
                address = intentData.schemeSpecificPart
            }
        }

        val mainIntent = Intent(this, MainActivity::class.java).apply {
            putExtra("sms_address", address)
            putExtra("sms_body", intent.getStringExtra(Intent.EXTRA_TEXT) ?: "")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(mainIntent)
        finish()
    }
}
