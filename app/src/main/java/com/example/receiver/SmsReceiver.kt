package com.example.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.repository.SpamFilterEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val smsBody = StringBuilder()
        val sender = messages[0].displayOriginatingAddress ?: "Unknown"
        val timestamp = messages[0].timestampMillis

        for (msg in messages) {
            smsBody.append(msg.displayMessageBody)
        }

        val body = smsBody.toString()

        // Write to system SMS Provider (Inbox) so other apps and our app can see it
        writeSmsToInbox(context, sender, body, timestamp)

        // Trigger Live UI update via local broadcast
        val updateIntent = Intent("com.example.SMS_RELOAD_UI").apply {
            `package` = context.packageName
        }
        context.sendBroadcast(updateIntent)

        // Perform Spam Classification using CoroutineScope on IO thread
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val spamFilterEngine = SpamFilterEngine(context)
                val isSpam = spamFilterEngine.isSpam(sender, body, emptySet()) // empty contacts check for quick async triage

                if (!isSpam) {
                    showSmsNotification(context, sender, body)
                    tryAutoCopyOtp(context, body)
                } else {
                    Log.d("SmsReceiver", "Spam message received and suppressed from notification: $sender")
                }
            } catch (e: Exception) {
                Log.e("SmsReceiver", "Error processing incoming SMS", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun tryAutoCopyOtp(context: Context, body: String) {
        val lowerBody = body.lowercase()
        val keywords = listOf(
            "کد تایید", "رمز پویا", "رمز یکبار مصرف", "کد ورود", "کد تائید", "کدفعالسازی",
            "otp", "verification code", "verification", "activation", "code", "confirm"
        )
        val hasKeyword = keywords.any { lowerBody.contains(it) }
        if (!hasKeyword) return

        val pattern = java.util.regex.Pattern.compile("\\b\\d{4,8}\\b")
        val matcher = pattern.matcher(body)
        if (matcher.find()) {
            val otpCode = matcher.group()
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("Nirvana_OTP", otpCode)
                    clipboard.setPrimaryClip(clip)
                    
                    val settings = com.example.data.NirvanaSettings(context)
                    val toastMessage = if (settings.appLanguage == "fa") {
                        "کد تأیید $otpCode به طور خودکار کپی شد"
                    } else {
                        "Verification code $otpCode copied automatically"
                    }
                    android.widget.Toast.makeText(context, toastMessage, android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("SmsReceiver", "Failed to copy OTP automatically", e)
            }
        }
    }

    private fun writeSmsToInbox(context: Context, sender: String, body: String, timestamp: Long) {
        val uri = Uri.parse("content://sms/inbox")
        val values = ContentValues().apply {
            put("address", sender)
            put("body", body)
            put("date", timestamp)
            put("date_sent", timestamp)
            put("read", 0) // Unread
            put("type", 1) // Inbox type
            put("seen", 0) // Unseen
        }
        try {
            val insertedUri = context.contentResolver.insert(uri, values)
            // Critical for auto-reading apps/websites: notify content observers of the database update!
            context.contentResolver.notifyChange(Uri.parse("content://sms"), null)
            context.contentResolver.notifyChange(Uri.parse("content://sms/inbox"), null)
            if (insertedUri != null) {
                context.contentResolver.notifyChange(insertedUri, null)
            }
        } catch (e: Exception) {
            Log.e("SmsReceiver", "Failed to insert incoming SMS to content provider", e)
        }
    }

    private fun showSmsNotification(context: Context, sender: String, body: String) {
        val settings = com.example.data.NirvanaSettings(context)

        // Suppress notifications for hidden senders
        val cleanSender = sender.replace("[^0-9]".toRegex(), "")
        val isHidden = settings.hiddenPhoneNumbers.any {
            val cleanHidden = it.replace("[^0-9]".toRegex(), "")
            cleanHidden == cleanSender || (cleanSender.length >= 10 && cleanHidden.takeLast(10) == cleanSender.takeLast(10))
        }
        if (isHidden) {
            Log.d("SmsReceiver", "Suppressing notification for hidden sender: $sender")
            return
        }

        if (com.example.MainActivity.isAppInForeground && com.example.MainActivity.activeThreadAddress == sender) {
            // User is actively looking at this conversation, do not show a notification!
            return
        }
        val displayBody = if (settings.hideNotificationContent) {
            if (settings.appLanguage == "fa") "پیام جدید" else "New Message"
        } else {
            body
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "nirvana_sms_channel"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Nirvana SMS",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming standard text messages"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val threadId = try {
            Telephony.Threads.getOrCreateThreadId(context, sender)
        } catch (e: Exception) {
            -1L
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("thread_id", threadId)
            putExtra("address", sender)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.sym_action_chat) // Fallback standard system chat icon
            .setContentTitle(sender)
            .setContentText(displayBody)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (!settings.hideNotificationContent) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }

        val notification = builder.build()

        notificationManager.notify(sender.hashCode(), notification)
    }

    private fun showSpamFilteredNotification(context: Context, sender: String, body: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "nirvana_spam_channel"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Nirvana Anti-Spam",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Filtered advertising and promotional SMS"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val threadId = try {
            Telephony.Threads.getOrCreateThreadId(context, sender)
        } catch (e: Exception) {
            -1L
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("thread_id", threadId)
            putExtra("address", sender)
            putExtra("navigate_to_spam", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 1, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("🛡️ Nirvana Spam Filtered")
            .setContentText("Message from $sender was filtered into the anti-advertising folder.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(sender.hashCode(), notification)
    }
}
