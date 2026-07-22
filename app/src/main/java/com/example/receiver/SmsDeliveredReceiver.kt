package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.ContentValues
import android.net.Uri
import android.util.Log

class SmsDeliveredReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.example.SMS_DELIVERED") {
            val messageId = intent.getLongExtra("message_id", -1L)
            Log.d("SmsDeliveredReceiver", "Received SMS delivery report for messageId: $messageId")
            if (messageId != -1L) {
                try {
                    val uri = Uri.parse("content://sms/$messageId")
                    val values = ContentValues().apply {
                        put("status", 0) // 0 = STATUS_COMPLETE (Delivered)
                    }
                    val updated = context.contentResolver.update(uri, values, null, null)
                    Log.d("SmsDeliveredReceiver", "Updated $updated rows in content resolver for messageId: $messageId")
                    
                    // Trigger Live UI update via local broadcast
                    val updateIntent = Intent("com.example.SMS_RELOAD_UI").apply {
                        `package` = context.packageName
                    }
                    context.sendBroadcast(updateIntent)
                } catch (e: Exception) {
                    Log.e("SmsDeliveredReceiver", "Failed to update delivery status", e)
                }
            }
        }
    }
}
