package com.example.data.repository

import android.content.Context
import com.example.data.database.SpamDatabase
import com.example.data.database.SpamRule
import com.example.data.database.MessageClassification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SpamFilterEngine(private val context: Context) {
    private val database = SpamDatabase.getDatabase(context)
    private val ruleDao = database.spamRuleDao()
    private val classificationDao = database.classificationDao()

    // Default Persian and English spam keywords
    private val defaultSpamKeywords = setOf(
        "تور", "برنده", "قرعه", "کشی", "قرعه‌کشی", "شارژ", "استخدام", "رایگان", 
        "ثبت نام", "خرید", "تخفیف", "ویژه", "درآمد", "پیشنهاد", "لغو", "پشتیبانی",
        "کسب و کار", "سرمایه", "فروش", "جشنواره", "رزرو", "هدیه", "جایزه", "وام",
        "سود", "بورس", "فیلتر", "ارسال عدد", "ارسال پیامک", "کد تخفیف", "آفورد",
        "تبلیغاتی", "تبلیغات", "پیامک تبلیغاتی", "off", "winner", "promo", "free",
        "lottery", "gift", "discount", "cashback", "bonus", "prize"
    )

    // Numbers starting with these prefixes are typically advertising shortcodes and bulk SMS gateways in Iran
    private val defaultSpamSenderPrefixes = listOf(
        "1000", "2000", "3000", "5000", "9000", "021", "026", 
        "9821", "+9821", "981000", "+981000", "982000", "+982000", 
        "983000", "+983000", "985000", "+985000", "989000", "+989000",
        "009821", "00981000", "00982000", "00983000", "00985000", "00989000"
    )

    suspend fun initializeDefaultRulesIfEmpty() {
        withContext(Dispatchers.IO) {
            val existingRules = ruleDao.getAllRules()
            if (existingRules.isEmpty()) {
                // Populate default keyword rules
                defaultSpamKeywords.forEach { keyword ->
                    ruleDao.insertRule(SpamRule(pattern = keyword, type = "KEYWORD", isBlacklist = true))
                }
                // Populate default prefix rules
                defaultSpamSenderPrefixes.forEach { prefix ->
                    ruleDao.insertRule(SpamRule(pattern = prefix, type = "SENDER", isBlacklist = true))
                }
            }
        }
    }

    suspend fun isSpam(sender: String, body: String, contacts: Set<String>): Boolean = withContext(Dispatchers.IO) {
        val cleanSender = normalizePhoneNumber(sender)

        // 1. Check cached classification in database (User manual overrides take absolute highest priority!)
        val cached = classificationDao.getClassificationForSender(sender) 
            ?: classificationDao.getClassificationForSender(cleanSender)
        if (cached != null && cached.userOverridden) {
            return@withContext cached.isSpam
        }

        // 1.5. Bank check: Banking messages are NEVER classified as spam automatically (unless manually overridden above)
        if (isBankMessage(sender, body)) {
            return@withContext false
        }

        // 2. Safe check: If the sender is in the user's contacts, it is NEVER spam (unless overridden above)
        val isContact = if (contacts.contains(cleanSender) || (cleanSender.length >= 10 && contacts.contains(cleanSender.takeLast(10)))) {
            true
        } else {
            contacts.any { normalizePhoneNumber(it) == cleanSender }
        }
        if (isContact) {
            return@withContext false
        }

        // 3. Otherwise use the automatically cached classification
        if (cached != null) {
            return@withContext cached.isSpam
        }

        // 3. Check custom database rules
        val databaseRules = ruleDao.getAllRules()
        
        // Check Whitelist (isBlacklist = false)
        val isWhitelisted = databaseRules.filter { !it.isBlacklist }.any { rule ->
            when (rule.type) {
                "SENDER" -> sender.contains(rule.pattern) || normalizePhoneNumber(sender) == normalizePhoneNumber(rule.pattern)
                "KEYWORD" -> body.contains(rule.pattern, ignoreCase = true)
                else -> false
            }
        }
        if (isWhitelisted) {
            // Save classification
            classificationDao.insertClassification(MessageClassification(sender, isSpam = false))
            return@withContext false
        }

        // Check Blacklist (isBlacklist = true)
        val isBlacklisted = databaseRules.filter { it.isBlacklist }.any { rule ->
            when (rule.type) {
                "SENDER" -> sender.startsWith(rule.pattern) || normalizePhoneNumber(sender) == normalizePhoneNumber(rule.pattern)
                "KEYWORD" -> body.contains(rule.pattern, ignoreCase = true)
                else -> false
            }
        }
        if (isBlacklisted) {
            classificationDao.insertClassification(MessageClassification(sender, isSpam = true))
            return@withContext true
        }

        // 4. Default Heuristic Checks
        val hasUnsubscribeKeyword = body.contains("لغو") || body.contains("عدد") || body.contains("off", ignoreCase = true)
        val isCommercialPrefix = defaultSpamSenderPrefixes.any { sender.startsWith(it) } ||
                                 defaultSpamSenderPrefixes.any { cleanSender.startsWith(it) } ||
                                 cleanSender.startsWith("021") ||
                                 cleanSender.startsWith("026") ||
                                 cleanSender.startsWith("01000") ||
                                 cleanSender.startsWith("02000") ||
                                 cleanSender.startsWith("03000") ||
                                 cleanSender.startsWith("05000") ||
                                 cleanSender.startsWith("09000")
        val isShortCode = sender.length <= 8 || isCommercialPrefix

        if (isCommercialPrefix || (isShortCode && (hasUnsubscribeKeyword || defaultSpamKeywords.any { body.contains(it, ignoreCase = true) }))) {
            classificationDao.insertClassification(MessageClassification(sender, isSpam = true))
            return@withContext true
        }

        // If no match, treat as safe/regular message
        classificationDao.insertClassification(MessageClassification(sender, isSpam = false))
        return@withContext false
    }

    suspend fun setUserClassification(sender: String, isSpam: Boolean) = withContext(Dispatchers.IO) {
        classificationDao.insertClassification(MessageClassification(sender, isSpam = isSpam, userOverridden = true))
        val cleanSender = normalizePhoneNumber(sender)
        if (cleanSender != sender) {
            classificationDao.insertClassification(MessageClassification(cleanSender, isSpam = isSpam, userOverridden = true))
        }
    }

    suspend fun resetClassifications() = withContext(Dispatchers.IO) {
        classificationDao.clearAll()
    }

    private fun normalizePhoneNumber(phone: String): String {
        var clean = phone.replace("[^0-9]".toRegex(), "")
        if (clean.startsWith("98")) {
            clean = "0" + clean.substring(2)
        } else if (clean.startsWith("+98")) {
            clean = "0" + clean.substring(3)
        }
        return clean
    }

    private fun isBankMessage(sender: String, body: String): Boolean {
        val lowerSender = sender.lowercase(java.util.Locale.ENGLISH)
        val lowerBody = body.lowercase(java.util.Locale.ENGLISH)
        val bankSenders = listOf(
            "mellat", "saman", "melli", "tejarat", "refah", "parsian", "pasargad", 
            "shahr", "sepah", "ansar", "ghavamin", "sina", "day", "gardeshgari", 
            "bki", "karafarin", "ayandeh", "melal", "noor", "postbank", "sadad", "resalat"
        )
        if (bankSenders.any { lowerSender.contains(it) }) {
            return true
        }

        // If the body contains "رسالت" or "resalat" or "بانک" (as a prefix or keyword) or any of the bank names
        val persianBankNames = listOf(
            "رسالت", "ملت", "ملی", "صادرات", "تجارت", "سپه", "مسکن", "کشاورزی", 
            "سامان", "پارسیان", "پاسارگاد", "کارآفرین", "سرمایه", "صنعت و معدن", 
            "توسعه تعاون", "پست بانک", "اقتصاد نوین", "سینا", "خاورمیانه", "شهر", 
            "دی", "رفاه", "مهر ایران", "قوامین", "انصار", "بلوبانک", "بلو بانک"
        )
        if (persianBankNames.any { body.contains(it) }) {
            return true
        }
        if (bankSenders.any { lowerBody.contains(it) }) {
            return true
        }

        val bankKeywords = listOf(
            "واریز", "برداشت", "مانده", "موجودی", "حساب", "انتقال", "کارت پویا", 
            "رمز پویا", "رمز یکبار", "پایا", "ساتنا", "شبا", "خرید", "کارمزد", 
            "تسهیلات", "قسط", "اقساط", "بانک"
        )

        val hasBankKeyword = bankKeywords.any { body.contains(it) }
        if (hasBankKeyword) {
            val hasAmountOrCard = body.contains("ریال") || 
                                  body.contains("تومان") || 
                                  body.contains("Rls") || 
                                  body.contains("Toman") ||
                                  body.contains("کارت") || 
                                  body.contains("شعبه") ||
                                  body.contains("حساب") ||
                                  body.contains("رمز") ||
                                  body.contains("یکبار مصرف") ||
                                  "\\d{4,16}".toRegex().containsMatchIn(body)
            if (hasAmountOrCard) {
                return true
            }
        }
        return false
    }
}
