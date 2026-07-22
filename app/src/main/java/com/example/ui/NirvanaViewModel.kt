package com.example.ui

import android.app.Activity
import android.app.Application
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.NirvanaSettings
import com.example.data.database.SpamDatabase
import com.example.data.database.SpamRule
import com.example.data.repository.SmsRepository
import com.example.data.repository.SmsThread
import com.example.data.repository.SmsMessage
import com.example.data.repository.NirvanaContact
import com.example.data.repository.SpamFilterEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ScheduledMessage(
    val id: String,
    val address: String,
    val body: String,
    val scheduleTime: Long,
    val threadId: Long?
)

data class PendingDelayedMessage(
    val address: String,
    val body: String,
    val threadId: Long?,
    val subId: Int?,
    val secondsRemaining: Int
)

class NirvanaViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val settings = NirvanaSettings(context)
    private val smsRepository = SmsRepository(context)
    private val spamFilterEngine = SpamFilterEngine(context)
    private val spamDb = SpamDatabase.getDatabase(context)

    // Configuration / Preferences States
    private val _themeMode = MutableStateFlow(settings.themeMode)
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _fontStyle = MutableStateFlow(settings.fontStyle)
    val fontStyle: StateFlow<String> = _fontStyle.asStateFlow()

    private val _fontSizeScale = MutableStateFlow(settings.fontSizeScale)
    val fontSizeScale: StateFlow<Float> = _fontSizeScale.asStateFlow()

    private val _isAutoSpamFilterEnabled = MutableStateFlow(settings.isAutoSpamFilterEnabled)
    val isAutoSpamFilterEnabled: StateFlow<Boolean> = _isAutoSpamFilterEnabled.asStateFlow()

    private val _delayedSendSeconds = MutableStateFlow(settings.delayedSendSeconds)
    val delayedSendSeconds: StateFlow<Int> = _delayedSendSeconds.asStateFlow()

    private val _hideNotificationContent = MutableStateFlow(settings.hideNotificationContent)
    val hideNotificationContent: StateFlow<Boolean> = _hideNotificationContent.asStateFlow()

    private val _isDeliveryReportEnabled = MutableStateFlow(settings.isDeliveryReportEnabled)
    val isDeliveryReportEnabled: StateFlow<Boolean> = _isDeliveryReportEnabled.asStateFlow()

    private val _appLanguage = MutableStateFlow(settings.appLanguage)
    val appLanguage: StateFlow<String> = _appLanguage.asStateFlow()

    private val _isDefaultBannerDismissed = MutableStateFlow(settings.isDefaultBannerDismissed)
    val isDefaultBannerDismissed: StateFlow<Boolean> = _isDefaultBannerDismissed.asStateFlow()

    private val _showMessagePreviewInList = MutableStateFlow(settings.showMessagePreviewInList)
    val showMessagePreviewInList: StateFlow<Boolean> = _showMessagePreviewInList.asStateFlow()

    fun setShowMessagePreviewInList(enabled: Boolean) {
        settings.showMessagePreviewInList = enabled
        _showMessagePreviewInList.value = enabled
    }

    private val _showContactNames = MutableStateFlow(settings.showContactNames)
    val showContactNames: StateFlow<Boolean> = _showContactNames.asStateFlow()

    fun setShowContactNames(enabled: Boolean) {
        settings.showContactNames = enabled
        _showContactNames.value = enabled
    }

    private val _isSwipeToReplyEnabled = MutableStateFlow(settings.isSwipeToReplyEnabled)
    val isSwipeToReplyEnabled: StateFlow<Boolean> = _isSwipeToReplyEnabled.asStateFlow()

    fun setSwipeToReplyEnabled(enabled: Boolean) {
        settings.isSwipeToReplyEnabled = enabled
        _isSwipeToReplyEnabled.value = enabled
    }

    private val _bubbleColorScheme = MutableStateFlow(settings.bubbleColorScheme)
    val bubbleColorScheme: StateFlow<String> = _bubbleColorScheme.asStateFlow()

    fun setBubbleColorScheme(scheme: String) {
        settings.bubbleColorScheme = scheme
        _bubbleColorScheme.value = scheme
    }

    private val _draftsMap = MutableStateFlow<Map<Long, String>>(settings.getAllDrafts())
    val draftsMap: StateFlow<Map<Long, String>> = _draftsMap.asStateFlow()

    fun getDraft(threadId: Long): String {
        return _draftsMap.value[threadId] ?: settings.getDraft(threadId)
    }

    fun saveDraft(threadId: Long, text: String) {
        settings.saveDraft(threadId, text)
        _draftsMap.value = settings.getAllDrafts()
        val draftTimes = settings.getAllDraftTimestamps()
        _threads.value = _threads.value.sortedByDescending { thread ->
            maxOf(thread.date, draftTimes[thread.threadId] ?: 0L)
        }
    }

    private val _showWelcomeDialog = MutableStateFlow(false)
    val showWelcomeDialog: StateFlow<Boolean> = _showWelcomeDialog.asStateFlow()

    fun dismissWelcomeDialog() {
        _showWelcomeDialog.value = false
    }

    private val _pendingDelayedMessage = MutableStateFlow<PendingDelayedMessage?>(null)
    val pendingDelayedMessage: StateFlow<PendingDelayedMessage?> = _pendingDelayedMessage.asStateFlow()

    private var delayedSendJob: kotlinx.coroutines.Job? = null

    // Promo/Ad States
    private val _promoText = MutableStateFlow(settings.promoText)
    val promoText: StateFlow<String> = _promoText.asStateFlow()

    private val _promoUrl = MutableStateFlow(settings.promoUrl)
    val promoUrl: StateFlow<String> = _promoUrl.asStateFlow()

    private val _promoFetchUrl = MutableStateFlow(settings.promoFetchUrl)
    val promoFetchUrl: StateFlow<String> = _promoFetchUrl.asStateFlow()

    // SIM slots States
    private val _activeSims = MutableStateFlow<List<com.example.data.repository.SimInfo>>(emptyList())
    val activeSims: StateFlow<List<com.example.data.repository.SimInfo>> = _activeSims.asStateFlow()

    // Scheduled Messages
    private val _scheduledMessages = MutableStateFlow<List<ScheduledMessage>>(emptyList())
    val scheduledMessages: StateFlow<List<ScheduledMessage>> = _scheduledMessages.asStateFlow()

    // SMS Content States
    private val _threads = MutableStateFlow<List<SmsThread>>(emptyList())
    val threads: StateFlow<List<SmsThread>> = _threads.asStateFlow()

    private val _spamThreads = MutableStateFlow<List<SmsThread>>(emptyList())
    val spamThreads: StateFlow<List<SmsThread>> = _spamThreads.asStateFlow()

    private val _hiddenThreads = MutableStateFlow<List<SmsThread>>(emptyList())
    val hiddenThreads: StateFlow<List<SmsThread>> = _hiddenThreads.asStateFlow()

    private val _securePin = MutableStateFlow(settings.securePin)
    val securePin: StateFlow<String> = _securePin.asStateFlow()

    private val _useBiometric = MutableStateFlow(settings.useBiometric)
    val useBiometric: StateFlow<Boolean> = _useBiometric.asStateFlow()

    private val _hiddenPhoneNumbers = MutableStateFlow<Set<String>>(settings.hiddenPhoneNumbers)
    val hiddenPhoneNumbers: StateFlow<Set<String>> = _hiddenPhoneNumbers.asStateFlow()

    private val _activeMessages = MutableStateFlow<List<SmsMessage>>(emptyList())
    val activeMessages: StateFlow<List<SmsMessage>> = _activeMessages.asStateFlow()

    private val _customSpamRules = MutableStateFlow<List<SpamRule>>(emptyList())
    val customSpamRules: StateFlow<List<SpamRule>> = _customSpamRules.asStateFlow()

    private val _savedMessages = MutableStateFlow<List<com.example.data.database.SavedMessage>>(emptyList())
    val savedMessages: StateFlow<List<com.example.data.database.SavedMessage>> = _savedMessages.asStateFlow()

    private val _contacts = MutableStateFlow<List<NirvanaContact>>(emptyList())
    val contacts: StateFlow<List<NirvanaContact>> = _contacts.asStateFlow()

    // System States
    private val _isDefaultSms = MutableStateFlow(false)
    val isDefaultSms: StateFlow<Boolean> = _isDefaultSms.asStateFlow()

    private val _hasPermissions = MutableStateFlow(false)
    val hasPermissions: StateFlow<Boolean> = _hasPermissions.asStateFlow()

    private var smsObserver: android.database.ContentObserver? = null

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        viewModelScope.launch {
            _isLoading.value = true
            
            // Check permissions and basic systems first
            checkSystemStates()
            
            // Launch other long-running tasks concurrently in background coroutines
            launch(Dispatchers.IO) {
                spamFilterEngine.initializeDefaultRulesIfEmpty()
            }
            launch(Dispatchers.IO) {
                loadCustomRules()
            }
            launch(Dispatchers.IO) {
                loadSavedMessages()
            }
            launch(Dispatchers.IO) {
                loadSims()
            }
            
            if (_hasPermissions.value) {
                // 1. Immediately load SMS threads so they show up in under 50ms
                loadAllSmsData()
                
                // 2. Concurrently fetch contacts in background.
                // When done, map contact names and refresh threads list.
                launch(Dispatchers.IO) {
                    smsRepository.loadContacts()
                    val contactsList = smsRepository.getContactsList()
                    withContext(Dispatchers.Main) {
                        _contacts.value = contactsList
                    }
                    loadAllSmsData()
                }
            }
            
            launch(Dispatchers.IO) {
                fetchPromoSilently()
            }
            
            // Hide full-screen blocking loader immediately
            _isLoading.value = false
        }
        startScheduledMessageTicker()
    }

    private fun startScheduledMessageTicker() {
        viewModelScope.launch {
            while (true) {
                try {
                    val now = System.currentTimeMillis()
                    val currentList = _scheduledMessages.value
                    val toSend = currentList.filter { it.scheduleTime <= now }
                    if (toSend.isNotEmpty()) {
                        _scheduledMessages.value = currentList.filter { it.scheduleTime > now }
                        for (msg in toSend) {
                            sendSmsMessage(msg.address, msg.body, msg.threadId)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "پیام زمان‌بندی شده به ${msg.address} ارسال شد.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                kotlinx.coroutines.delay(4000) // check every 4 seconds
            }
        }
    }

    fun loadSims() {
        _activeSims.value = smsRepository.getActiveSimSlots()
    }

    fun addScheduledMessage(address: String, body: String, delaySeconds: Long, threadId: Long?) {
        val scheduleTime = System.currentTimeMillis() + (delaySeconds * 1000)
        val id = System.nanoTime().toString()
        val newMsg = ScheduledMessage(id, address, body, scheduleTime, threadId)
        _scheduledMessages.value = _scheduledMessages.value + newMsg
        Toast.makeText(context, "پیام برای ارسال در ${delaySeconds} ثانیه دیگر زمان‌بندی شد.", Toast.LENGTH_SHORT).show()
    }

    fun cancelScheduledMessage(msgId: String) {
        _scheduledMessages.value = _scheduledMessages.value.filter { it.id != msgId }
        Toast.makeText(context, "ارسال پیام زمان‌بندی شده لغو شد.", Toast.LENGTH_SHORT).show()
    }

    fun checkSystemStates() {
        val defaultSms = isDefaultSmsApp(context)
        val wasDefault = _isDefaultSms.value
        _isDefaultSms.value = defaultSms

        if (!defaultSms) {
            settings.isDefaultBannerDismissed = false
            _isDefaultBannerDismissed.value = false
        }

        val readSmsGranted = ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        
        _hasPermissions.value = readSmsGranted

        if (readSmsGranted) {
            registerSmsObserver()
        }

        if (defaultSms && !wasDefault) {
            loadAllSmsData()
        }

        if (defaultSms && !settings.welcomeMessageSent) {
            settings.welcomeMessageSent = true
            _showWelcomeDialog.value = true
            loadAllSmsData()
        }
    }

    private fun isDefaultSmsApp(context: Context): Boolean {
        val defaultPackage = Telephony.Sms.getDefaultSmsPackage(context)
        return context.packageName == defaultPackage
    }

    fun requestDefaultSmsRole(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = activity.getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                if (!roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                    activity.startActivityForResult(intent, 2026)
                }
            }
        } else {
            val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, activity.packageName)
            }
            activity.startActivityForResult(intent, 2026)
        }
    }

    fun onPermissionsGranted() {
        _hasPermissions.value = true
        registerSmsObserver()
        viewModelScope.launch {
            _isLoading.value = true
            smsRepository.loadContacts()
            _contacts.value = smsRepository.getContactsList()
            loadAllSmsData()
            loadSims()
            _isLoading.value = false
        }
    }

    fun registerSmsObserver() {
        // No-op to prevent infinite recursive database loop on thread updates.
        // SMS updates are already reliably captured via com.example.SMS_RELOAD_UI and ViewModel operations.
    }

    override fun onCleared() {
        super.onCleared()
        smsObserver?.let {
            try {
                context.contentResolver.unregisterContentObserver(it)
                smsObserver = null
                android.util.Log.d("NirvanaViewModel", "Unregistered SMS ContentObserver successfully.")
            } catch (e: Exception) {
                android.util.Log.e("NirvanaViewModel", "Failed to unregister SMS ContentObserver", e)
            }
        }
    }

    fun fetchPromoFromCpanel(fetchUrl: String? = null) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val urlString = fetchUrl ?: settings.promoFetchUrl
                if (urlString.isBlank()) {
                    _isLoading.value = false
                    return@launch
                }
                if (fetchUrl != null) {
                    settings.promoFetchUrl = fetchUrl
                    _promoFetchUrl.value = fetchUrl
                }

                withContext(Dispatchers.IO) {
                    if (urlString.endsWith(".txt")) {
                        // Legacy mode: Raw text file fetch
                        val result = java.net.URL(urlString).readText()
                        if (result.isNotBlank()) {
                            settings.promoText = result.trim()
                            _promoText.value = settings.promoText
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "تبلیغ با موفقیت از هاست سی‌پنل دریافت شد!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        // Advanced cPanel Backend mode!
                        val baseUrl = if (urlString.endsWith("/")) urlString else "$urlString/"
                        val apiUrl = if (urlString.contains("api.php")) urlString else "${baseUrl}api.php"

                        // 1. Send Ping / Install Stat to cPanel
                        try {
                            val pingUrl = java.net.URL("${apiUrl}?action=ping")
                            val conn = pingUrl.openConnection() as java.net.HttpURLConnection
                            conn.requestMethod = "POST"
                            conn.doOutput = true
                            conn.connectTimeout = 6000
                            conn.readTimeout = 6000
                            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                            
                            val postData = "uuid=${java.net.URLEncoder.encode(settings.deviceUuid, "UTF-8")}" +
                                           "&model=${java.net.URLEncoder.encode(android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL, "UTF-8")}" +
                                           "&os_version=${java.net.URLEncoder.encode("Android " + android.os.Build.VERSION.RELEASE, "UTF-8")}"
                            
                            conn.outputStream.use { os ->
                                os.write(postData.toByteArray(charset("UTF-8")))
                            }
                            val responseCode = conn.responseCode
                            android.util.Log.d("NirvanaSync", "Ping response: $responseCode")
                        } catch (e: Exception) {
                            android.util.Log.e("NirvanaSync", "Ping failed", e)
                        }

                        // 2. Fetch Promo Data and Remote Spam/Ad Rules
                        val getUrl = java.net.URL("${apiUrl}?action=get_data")
                        val getConn = getUrl.openConnection() as java.net.HttpURLConnection
                        getConn.requestMethod = "GET"
                        getConn.connectTimeout = 6000
                        getConn.readTimeout = 6000
                        
                        val jsonStr = getConn.inputStream.bufferedReader().use { it.readText() }
                        val responseJson = org.json.JSONObject(jsonStr)
                        
                        val promoText = responseJson.optString("promo_text", "")
                        val promoUrl = responseJson.optString("promo_url", "")
                        
                        if (promoText.isNotBlank()) {
                            settings.promoText = promoText
                            _promoText.value = promoText
                        }
                        if (promoUrl.isNotBlank()) {
                            settings.promoUrl = promoUrl
                            _promoUrl.value = promoUrl
                        }

                        // Parse and integrate remote rules
                        val spamRulesArr = responseJson.optJSONArray("spam_rules")
                        if (spamRulesArr != null) {
                            val existingRules = spamDb.spamRuleDao().getAllRules()
                            for (i in 0 until spamRulesArr.length()) {
                                val ruleObj = spamRulesArr.getJSONObject(i)
                                val pattern = ruleObj.getString("pattern")
                                val type = ruleObj.optString("type", "KEYWORD")
                                val isBlacklist = ruleObj.optBoolean("is_blacklist", true)

                                val alreadyExists = existingRules.any { 
                                    it.pattern.equals(pattern, ignoreCase = true) && it.type == type 
                                }
                                if (!alreadyExists) {
                                    spamDb.spamRuleDao().insertRule(
                                        com.example.data.database.SpamRule(
                                            pattern = pattern,
                                            type = type,
                                            isBlacklist = isBlacklist
                                        )
                                    )
                                }
                            }
                            spamFilterEngine.resetClassifications()
                        }

                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "همگام‌سازی و آمار با سی‌پنل موفقیت‌آمیز بود!", Toast.LENGTH_SHORT).show()
                            loadAllSmsData()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    val errorMsg = if (e is java.io.FileNotFoundException || e.message?.contains("404") == true) {
                        if (settings.appLanguage == "fa") {
                            "خطای ۴۰۴: فایل api.php یا فایل متنی تبلیغ در هاست سی‌پنل یافت نشد. مطمئن شوید فایل api.php را در هاست خود قرار داده‌اید."
                        } else {
                            "Error 404: The api.php or text file was not found on your cPanel host. Please verify you uploaded the file."
                        }
                    } else {
                        if (settings.appLanguage == "fa") {
                            "خطا در اتصال به سی‌پنل: ${e.message}"
                        } else {
                            "Error connecting to cPanel: ${e.message}"
                        }
                    }
                    Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun savePromoSettings(text: String, url: String, fetchUrl: String) {
        settings.promoText = text
        settings.promoUrl = url
        settings.promoFetchUrl = fetchUrl
        _promoText.value = text
        _promoUrl.value = url
        _promoFetchUrl.value = fetchUrl
        Toast.makeText(context, "تنظیمات تبلیغ ذخیره شد.", Toast.LENGTH_SHORT).show()
    }

    fun loadAllSmsData() {
        viewModelScope.launch {
            if (!_hasPermissions.value) return@launch
            try {
                val (regular, spam) = smsRepository.getAllSmsThreads()
                val hiddenIds = settings.hiddenThreadIds
                val hiddenPhones = settings.hiddenPhoneNumbers
                val hiddenPhonesNormalized = hiddenPhones.map { normalizePhoneNumber(it) }.toSet()
                
                val (hiddenRegular, visibleRegular) = regular.partition { 
                    hiddenIds.contains(it.threadId) || 
                    hiddenPhones.contains(it.address) || 
                    hiddenPhonesNormalized.contains(normalizePhoneNumber(it.address))
                }
                val (hiddenSpam, visibleSpam) = spam.partition { 
                    hiddenIds.contains(it.threadId) || 
                    hiddenPhones.contains(it.address) || 
                    hiddenPhonesNormalized.contains(normalizePhoneNumber(it.address))
                }
                
                val draftTimes = settings.getAllDraftTimestamps()
                val sortedRegular = visibleRegular.sortedByDescending { thread ->
                    maxOf(thread.date, draftTimes[thread.threadId] ?: 0L)
                }
                val sortedSpam = visibleSpam.sortedByDescending { thread ->
                    maxOf(thread.date, draftTimes[thread.threadId] ?: 0L)
                }

                _threads.value = sortedRegular
                _spamThreads.value = sortedSpam
                _hiddenThreads.value = hiddenRegular + hiddenSpam
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    suspend fun getOrCreateThreadId(address: String): Long = withContext(Dispatchers.IO) {
        return@withContext try {
            Telephony.Threads.getOrCreateThreadId(context, address)
        } catch (e: Exception) {
            e.printStackTrace()
            _threads.value.firstOrNull { it.address == address }?.threadId 
                ?: _spamThreads.value.firstOrNull { it.address == address }?.threadId 
                ?: System.currentTimeMillis()
        }
    }

    fun getContactName(address: String): String {
        return smsRepository.getContactName(address)
    }

    fun getContactPhotoUri(address: String, body: String? = null): String? {
        return smsRepository.getContactPhotoUri(address, body)
    }

    fun loadMessagesForThread(threadId: Long) {
        viewModelScope.launch {
            if (!_hasPermissions.value) return@launch
            try {
                val messages = smsRepository.getMessagesForThread(threadId)
                _activeMessages.value = messages
                // Refresh list values (such as read status counts) in background
                loadAllSmsData()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun sendSmsMessage(address: String, body: String, threadId: Long?, subId: Int? = null) {
        val delay = settings.delayedSendSeconds
        if (delay > 0) {
            cancelDelayedSend()
            delayedSendJob = viewModelScope.launch {
                for (i in delay downTo 1) {
                    _pendingDelayedMessage.value = PendingDelayedMessage(address, body, threadId, subId, i)
                    kotlinx.coroutines.delay(1000)
                }
                _pendingDelayedMessage.value = null
                performActualSend(address, body, threadId, subId)
            }
        } else {
            performActualSend(address, body, threadId, subId)
        }
    }

    private fun performActualSend(address: String, body: String, threadId: Long?, subId: Int?) {
        viewModelScope.launch {
            if (body.isBlank()) return@launch
            val success = smsRepository.sendSms(address, body, subId)
            if (success && threadId != null) {
                loadMessagesForThread(threadId)
            } else if (success) {
                // Sent to a new number, reload conversations list to discover new thread
                loadAllSmsData()
            }
        }
    }

    fun sendNowDelayed() {
        val pending = _pendingDelayedMessage.value ?: return
        cancelDelayedSend()
        performActualSend(pending.address, pending.body, pending.threadId, pending.subId)
    }

    fun cancelDelayedSend() {
        delayedSendJob?.cancel()
        delayedSendJob = null
        _pendingDelayedMessage.value = null
    }

    // --- Spam Rule Operations ---

    private fun loadSavedMessages() {
        viewModelScope.launch {
            spamDb.savedMessageDao().getAllSavedMessagesFlow().collect { messages ->
                _savedMessages.value = messages
            }
        }
    }

    fun saveMessage(sender: String, senderName: String?, body: String, timestamp: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            spamDb.savedMessageDao().insertSavedMessage(
                com.example.data.database.SavedMessage(
                    sender = sender,
                    senderName = senderName,
                    body = body,
                    timestamp = timestamp
                )
            )
        }
    }

    fun deleteSavedMessage(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            spamDb.savedMessageDao().deleteSavedMessageById(id)
        }
    }

    private fun loadCustomRules() {
        viewModelScope.launch {
            spamDb.spamRuleDao().getAllRulesFlow().collect { rules ->
                // Filter down to display non-default rules or simply show all for full transparency!
                _customSpamRules.value = rules
            }
        }
    }

    fun addNewSpamRule(pattern: String, type: String, isBlacklist: Boolean) {
        viewModelScope.launch {
            if (pattern.isBlank()) return@launch
            spamDb.spamRuleDao().insertRule(SpamRule(pattern = pattern, type = type, isBlacklist = isBlacklist))
            // Force re-classification of matching messages by wiping cache
            spamFilterEngine.resetClassifications()
            loadAllSmsData()
        }
    }

    fun deleteSpamRule(rule: SpamRule) {
        viewModelScope.launch {
            spamDb.spamRuleDao().deleteRule(rule)
            spamFilterEngine.resetClassifications()
            loadAllSmsData()
        }
    }

    fun resetToDefaultSpamRules() {
        viewModelScope.launch {
            _isLoading.value = true
            spamFilterEngine.resetClassifications()
            // Clear current and insert defaults
            withContext(Dispatchers.IO) {
                spamDb.clearAllTables()
            }
            spamFilterEngine.initializeDefaultRulesIfEmpty()
            loadAllSmsData()
            _isLoading.value = false
        }
    }

    fun toggleSpamStatusForThread(thread: SmsThread) {
        viewModelScope.launch {
            _isLoading.value = true
            val targetStatus = !thread.isSpam
            spamFilterEngine.setUserClassification(thread.address, targetStatus)
            loadAllSmsData()
            _isLoading.value = false
        }
    }

    fun toggleSpamStatusForThreads(threadIds: List<Long>, targetSpamStatus: Boolean) {
        viewModelScope.launch {
            _isLoading.value = true
            val allThreads = _threads.value + _spamThreads.value + _hiddenThreads.value
            val targetThreads = allThreads.filter { threadIds.contains(it.threadId) }
            targetThreads.forEach { thread ->
                spamFilterEngine.setUserClassification(thread.address, targetSpamStatus)
            }
            loadAllSmsData()
            _isLoading.value = false
        }
    }

    fun hideThread(threadId: Long) {
        val currentHidden = settings.hiddenThreadIds.toMutableSet()
        currentHidden.add(threadId)
        settings.hiddenThreadIds = currentHidden

        // Also add address to hidden phone numbers
        val thread = (_threads.value + _spamThreads.value + _hiddenThreads.value).find { it.threadId == threadId }
        thread?.let {
            val phoneNumbers = settings.hiddenPhoneNumbers.toMutableSet()
            phoneNumbers.add(it.address)
            settings.hiddenPhoneNumbers = phoneNumbers
        }

        loadAllSmsData()
    }

    fun unhideThread(threadId: Long) {
        val currentHidden = settings.hiddenThreadIds.toMutableSet()
        currentHidden.remove(threadId)
        settings.hiddenThreadIds = currentHidden

        // Also remove address from hidden phone numbers
        val thread = (_threads.value + _spamThreads.value + _hiddenThreads.value).find { it.threadId == threadId }
        thread?.let {
            val phoneNumbers = settings.hiddenPhoneNumbers.toMutableSet()
            phoneNumbers.remove(it.address)
            settings.hiddenPhoneNumbers = phoneNumbers
        }

        loadAllSmsData()
    }

    fun addHiddenPhoneNumber(phone: String) {
        val current = settings.hiddenPhoneNumbers.toMutableSet()
        current.add(phone)
        settings.hiddenPhoneNumbers = current
        _hiddenPhoneNumbers.value = current

        // Also add all matching threads to hiddenThreadIds so they are immediately moved/hidden
        val matchingThreads = (_threads.value + _spamThreads.value).filter {
            it.address == phone || 
            normalizePhoneNumber(it.address) == normalizePhoneNumber(phone)
        }
        if (matchingThreads.isNotEmpty()) {
            val currentHiddenThreads = settings.hiddenThreadIds.toMutableSet()
            matchingThreads.forEach { currentHiddenThreads.add(it.threadId) }
            settings.hiddenThreadIds = currentHiddenThreads
        }

        loadAllSmsData()
    }

    fun removeHiddenPhoneNumber(phone: String) {
        val current = settings.hiddenPhoneNumbers.toMutableSet()
        current.remove(phone)
        settings.hiddenPhoneNumbers = current
        _hiddenPhoneNumbers.value = current

        // Also remove all matching threads from hiddenThreadIds
        val matchingThreads = _hiddenThreads.value.filter {
            it.address == phone || 
            normalizePhoneNumber(it.address) == normalizePhoneNumber(phone)
        }
        if (matchingThreads.isNotEmpty()) {
            val currentHiddenThreads = settings.hiddenThreadIds.toMutableSet()
            matchingThreads.forEach { currentHiddenThreads.remove(it.threadId) }
            settings.hiddenThreadIds = currentHiddenThreads
        }

        loadAllSmsData()
    }

    fun updateSecurePin(pin: String) {
        settings.securePin = pin
        _securePin.value = pin
    }

    fun updateUseBiometric(enabled: Boolean) {
        settings.useBiometric = enabled
        _useBiometric.value = enabled
    }

    fun deleteThread(threadId: Long) {
        viewModelScope.launch {
            val success = smsRepository.deleteThread(threadId)
            if (success) {
                loadAllSmsData()
            }
        }
    }

    fun deleteMessage(messageId: Long, threadId: Long) {
        viewModelScope.launch {
            try {
                val success = smsRepository.deleteMessage(messageId)
                if (success) {
                    loadMessagesForThread(threadId)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // --- Settings Preferences Operations ---

    fun setTheme(theme: String) {
        settings.themeMode = theme
        _themeMode.value = theme
    }

    fun setFont(font: String) {
        settings.fontStyle = font
        _fontStyle.value = font
    }

    fun setFontSize(size: Float) {
        settings.fontSizeScale = size
        _fontSizeScale.value = size
    }

    fun toggleAutoSpamFilter(enabled: Boolean) {
        settings.isAutoSpamFilterEnabled = enabled
        _isAutoSpamFilterEnabled.value = enabled
        viewModelScope.launch {
            spamFilterEngine.resetClassifications()
            loadAllSmsData()
        }
    }

    fun setLanguage(lang: String) {
        settings.appLanguage = lang
        _appLanguage.value = lang
    }

    fun setDelayedSendSeconds(seconds: Int) {
        settings.delayedSendSeconds = seconds
        _delayedSendSeconds.value = seconds
    }

    fun setHideNotificationContent(hide: Boolean) {
        settings.hideNotificationContent = hide
        _hideNotificationContent.value = hide
    }

    fun setDeliveryReportEnabled(enabled: Boolean) {
        settings.isDeliveryReportEnabled = enabled
        _isDeliveryReportEnabled.value = enabled
    }

    fun dismissDefaultBanner() {
        settings.isDefaultBannerDismissed = true
        _isDefaultBannerDismissed.value = true
    }

    private fun fetchPromoSilently() {
        viewModelScope.launch {
            try {
                val urlString = settings.promoFetchUrl
                if (urlString.isNotBlank() && urlString != "https://your-cpanel-domain.com/ad.txt" && urlString != "https://yourcpanel.com/api/ad.json") {
                    val result = withContext(Dispatchers.IO) {
                        try {
                            java.net.URL(urlString).readText()
                        } catch (e: Exception) {
                            ""
                        }
                    }
                    if (result.isNotBlank()) {
                        settings.promoText = result.trim()
                        _promoText.value = settings.promoText
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showWelcomeNotification(context: Context, body: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "nirvana_sms_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "Nirvana SMS",
                android.app.NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }
        val intent = Intent(context, com.example.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            context, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val builder = androidx.core.app.NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle("Nirvana")
            .setContentText(body)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))
            
        notificationManager.notify(2026, builder.build())
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
}
