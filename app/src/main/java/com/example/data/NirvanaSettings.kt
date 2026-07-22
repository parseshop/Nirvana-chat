package com.example.data

import android.content.Context
import android.content.SharedPreferences

class NirvanaSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("nirvana_settings", Context.MODE_PRIVATE)

    companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_FONT = "font_style"
        const val KEY_FONT_SIZE = "font_size_scale"
        const val KEY_SPAM_FILTER = "auto_spam_filter"
        const val KEY_LANGUAGE = "app_language" // "fa" for Persian, "en" for English
    }

    var themeMode: String
        get() = prefs.getString(KEY_THEME, "lavender") ?: "lavender"
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    var fontStyle: String
        get() = prefs.getString(KEY_FONT, "Default") ?: "Default"
        set(value) = prefs.edit().putString(KEY_FONT, value).apply()

    var fontSizeScale: Float
        get() = prefs.getFloat(KEY_FONT_SIZE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_FONT_SIZE, value).apply()

    var isAutoSpamFilterEnabled: Boolean
        get() = prefs.getBoolean(KEY_SPAM_FILTER, true)
        set(value) = prefs.edit().putBoolean(KEY_SPAM_FILTER, value).apply()

    var appLanguage: String
        get() = prefs.getString(KEY_LANGUAGE, "fa") ?: "fa"
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    var isDefaultBannerDismissed: Boolean
        get() = prefs.getBoolean("is_default_banner_dismissed", false)
        set(value) = prefs.edit().putBoolean("is_default_banner_dismissed", value).apply()

    var welcomeMessageSent: Boolean
        get() = prefs.getBoolean("welcome_message_sent", false)
        set(value) = prefs.edit().putBoolean("welcome_message_sent", value).apply()

    var delayedSendSeconds: Int
        get() = prefs.getInt("delayed_send_seconds", 0)
        set(value) = prefs.edit().putInt("delayed_send_seconds", value).apply()

    var hideNotificationContent: Boolean
        get() = prefs.getBoolean("hide_notification_content", false)
        set(value) = prefs.edit().putBoolean("hide_notification_content", value).apply()

    var isDeliveryReportEnabled: Boolean
        get() = prefs.getBoolean("delivery_report_enabled", true)
        set(value) = prefs.edit().putBoolean("delivery_report_enabled", value).apply()

    var promoText: String
        get() = prefs.getString("promo_text", "به پیام‌رسان پیشرفته و امن نیروانا خوش آمدید! 🌸") ?: "به پیام‌رسان پیشرفته و امن نیروانا خوش آمدید! 🌸"
        set(value) = prefs.edit().putString("promo_text", value).apply()

    var promoUrl: String
        get() = prefs.getString("promo_url", "https://ai.studio/build") ?: "https://ai.studio/build"
        set(value) = prefs.edit().putString("promo_url", value).apply()

    var promoFetchUrl: String
        get() = prefs.getString("promo_fetch_url", "https://your-cpanel-domain.com/ad.txt") ?: "https://your-cpanel-domain.com/ad.txt"
        set(value) = prefs.edit().putString("promo_fetch_url", value).apply()

    var deviceUuid: String
        get() {
            var uuid = prefs.getString("device_uuid", "") ?: ""
            if (uuid.isEmpty()) {
                uuid = java.util.UUID.randomUUID().toString()
                prefs.edit().putString("device_uuid", uuid).apply()
            }
            return uuid
        }
        set(value) = prefs.edit().putString("device_uuid", value).apply()

    var securePin: String
        get() = prefs.getString("secure_pin", "") ?: ""
        set(value) = prefs.edit().putString("secure_pin", value).apply()

    var useBiometric: Boolean
        get() = prefs.getBoolean("use_biometric", false)
        set(value) = prefs.edit().putBoolean("use_biometric", value).apply()

    var hiddenThreadIds: Set<Long>
        get() {
            val raw = prefs.getString("hidden_thread_ids", "") ?: ""
            if (raw.isEmpty()) return emptySet()
            return raw.split(",").mapNotNull { it.toLongOrNull() }.toSet()
        }
        set(value) {
            val raw = value.joinToString(",")
            prefs.edit().putString("hidden_thread_ids", raw).apply()
        }

    var hiddenPhoneNumbers: Set<String>
        get() {
            val raw = prefs.getString("hidden_phone_numbers", "") ?: ""
            if (raw.isEmpty()) return emptySet()
            return raw.split(",").filter { it.isNotEmpty() }.toSet()
        }
        set(value) {
            val raw = value.filter { it.isNotEmpty() }.joinToString(",")
            prefs.edit().putString("hidden_phone_numbers", raw).apply()
        }

    var showMessagePreviewInList: Boolean
        get() = prefs.getBoolean("show_message_preview_in_list", true)
        set(value) = prefs.edit().putBoolean("show_message_preview_in_list", value).apply()

    var showContactNames: Boolean
        get() = prefs.getBoolean("show_contact_names", true)
        set(value) = prefs.edit().putBoolean("show_contact_names", value).apply()

    var isSwipeToReplyEnabled: Boolean
        get() = prefs.getBoolean("swipe_to_reply_enabled", true)
        set(value) = prefs.edit().putBoolean("swipe_to_reply_enabled", value).apply()

    var bubbleColorScheme: String
        get() = prefs.getString("bubble_color_scheme", "default") ?: "default"
        set(value) = prefs.edit().putString("bubble_color_scheme", value).apply()

    var isPinchZoomEnabled: Boolean
        get() = prefs.getBoolean("pinch_zoom_enabled", true)
        set(value) = prefs.edit().putBoolean("pinch_zoom_enabled", value).apply()

    fun getDraft(threadId: Long): String {
        return prefs.getString("draft_thread_$threadId", "") ?: ""
    }

    fun getDraftTimestamp(threadId: Long): Long {
        return prefs.getLong("draft_time_$threadId", 0L)
    }

    fun saveDraft(threadId: Long, text: String) {
        if (text.isBlank()) {
            prefs.edit().remove("draft_thread_$threadId").remove("draft_time_$threadId").apply()
        } else {
            val now = System.currentTimeMillis()
            prefs.edit()
                .putString("draft_thread_$threadId", text)
                .putLong("draft_time_$threadId", now)
                .apply()
        }
    }

    fun getAllDrafts(): Map<Long, String> {
        val result = mutableMapOf<Long, String>()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("draft_thread_")) {
                val threadId = key.removePrefix("draft_thread_").toLongOrNull()
                val text = value as? String
                if (threadId != null && !text.isNullOrBlank()) {
                    result[threadId] = text
                }
            }
        }
        return result
    }

    fun getAllDraftTimestamps(): Map<Long, Long> {
        val result = mutableMapOf<Long, Long>()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("draft_time_")) {
                val threadId = key.removePrefix("draft_time_").toLongOrNull()
                val timestamp = value as? Long
                if (threadId != null && timestamp != null && timestamp > 0L) {
                    result[threadId] = timestamp
                }
            }
        }
        return result
    }
}
