package com.example.service

import android.app.IntentService
import android.content.Intent
import android.util.Log

class HeadlessSmsSendService : IntentService("HeadlessSmsSendService") {
    @Deprecated("Deprecated in Java")
    override fun onHandleIntent(intent: Intent?) {
        Log.d("HeadlessSmsSendService", "Sending message via headless service is not implemented.")
    }
}
