package com.example.data.repository

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.provider.Telephony
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.database.SpamDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SimInfo(
    val id: Int,
    val slotIndex: Int,
    val displayName: String,
    val carrierName: String
)

data class SmsThread(
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val unreadCount: Int,
    var isSpam: Boolean = false,
    var senderName: String? = null,
    val photoUri: String? = null
)

data class SmsMessage(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val type: Int, // 1 = Received/Inbox, 2 = Sent
    val read: Int,
    val status: Int = -1
)

data class NirvanaContact(
    val name: String,
    val phoneNumber: String
)

class SmsRepository(private val context: Context) {
    private val spamFilterEngine = SpamFilterEngine(context)

    // In-memory cache of contacts for fast name-resolution
    private var contactMap = mapOf<String, String>()
    private var contactPhotoMap = mapOf<String, String>()

    suspend fun loadContacts() = withContext(Dispatchers.IO) {
        val tempMap = mutableMapOf<String, String>()
        val tempPhotoMap = mutableMapOf<String, String>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI
        )
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val photoIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI)
                while (cursor.moveToNext()) {
                    val rawNum = cursor.getString(numberIdx) ?: continue
                    val name = cursor.getString(nameIdx) ?: continue
                    val photoUri = if (photoIdx != -1) cursor.getString(photoIdx) else null
                    val cleanNum = rawNum.replace("[^0-9]".toRegex(), "")
                    tempMap[cleanNum] = name
                    if (photoUri != null) {
                        tempPhotoMap[cleanNum] = photoUri
                    }
                    // Also store with standard 10 digit representation for fallback matching
                    if (cleanNum.length >= 10) {
                        tempMap[cleanNum.takeLast(10)] = name
                        if (photoUri != null) {
                            tempPhotoMap[cleanNum.takeLast(10)] = photoUri
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e("SmsRepository", "No contact permissions", e)
        }
        contactMap = tempMap
        contactPhotoMap = tempPhotoMap
    }

    fun getContactsList(): List<NirvanaContact> {
        val list = mutableListOf<NirvanaContact>()
        val processedNumbers = mutableSetOf<String>()
        for ((number, name) in contactMap) {
            // Filter out 10-digit suffix keys to make the list clean and avoid duplicate listings
            if (number.length >= 7 && !processedNumbers.contains(number)) {
                list.add(NirvanaContact(name = name, phoneNumber = number))
                processedNumbers.add(number)
            }
        }
        return list.sortedBy { it.name }
    }

    fun getContactName(address: String): String {
        val clean = address.replace("[^0-9]".toRegex(), "")
        if (clean.isEmpty()) return address
        
        // Search exact or suffix match
        return contactMap[clean] 
            ?: contactMap[clean.takeLast(10)] 
            ?: address
    }

    fun getContactNameForMissedCall(address: String, body: String): String {
        val defaultName = getContactName(address)
        val isMissedCall = body.contains("تماس بی پاسخ") || body.contains("تماس بی‌پاسخ") || 
                body.contains("تماس از") || body.contains("missed call", ignoreCase = true) || 
                body.contains("تماسبان") || body.contains("تماس‌بان")
        if (isMissedCall) {
            return "تماس بی‌پاسخ"
        }
        return defaultName
    }

    fun processMessageBodyForMissedCall(body: String): String {
        val isMissedCall = body.contains("تماس بی پاسخ") || body.contains("تماس بی‌پاسخ") || 
                body.contains("تماس از") || body.contains("missed call", ignoreCase = true) || 
                body.contains("تماسبان") || body.contains("تماس‌بان")
        if (isMissedCall) {
            val normalizedBody = body.replace('۰', '0').replace('۱', '1').replace('۲', '2').replace('۳', '3').replace('۴', '4')
                .replace('۵', '5').replace('۶', '6').replace('۷', '7').replace('۸', '8').replace('۹', '9')
            val regex = "(\\+?989\\d{9}|09\\d{9}|9\\d{9})".toRegex()
            var processedBody = body
            regex.findAll(normalizedBody).forEach { match ->
                val foundNum = match.value
                val cleanFound = foundNum.replace("[^0-9]".toRegex(), "")
                val cleanNum = if (cleanFound.startsWith("98")) {
                    "0" + cleanFound.substring(2)
                } else if (cleanFound.startsWith("0")) {
                    cleanFound
                } else if (cleanFound.startsWith("9") && cleanFound.length == 10) {
                    "0" + cleanFound
                } else {
                    cleanFound
                }
                val contactName = contactMap[cleanNum] ?: contactMap[cleanNum.takeLast(10)]
                if (contactName != null) {
                    // Try replacing either normalized foundNum or match in processedBody
                    if (processedBody.contains(foundNum)) {
                        processedBody = processedBody.replace(foundNum, "$contactName ($cleanNum)")
                    }
                }
            }
            return processedBody
        }
        return body
    }

    fun getContactPhotoUri(address: String, body: String? = null): String? {
        val clean = address.replace("[^0-9]".toRegex(), "")
        if (clean.isNotEmpty()) {
            val directPhoto = contactPhotoMap[clean] ?: contactPhotoMap[clean.takeLast(10)]
            if (directPhoto != null) return directPhoto
        }
        if (body != null) {
            val isMissedCall = body.contains("تماس بی پاسخ") || body.contains("تماس بی‌پاسخ") || 
                    body.contains("تماس از") || body.contains("missed call", ignoreCase = true) || 
                    body.contains("تماسبان") || body.contains("تماس‌بان")
            if (isMissedCall) {
                val regex = "(\\+?989\\d{9}|09\\d{9}|9\\d{9})".toRegex()
                val match = regex.find(body)
                if (match != null) {
                    val foundNum = match.value
                    val cleanFound = foundNum.replace("[^0-9]".toRegex(), "")
                    val cleanNum = if (cleanFound.startsWith("98")) {
                        "0" + cleanFound.substring(2)
                    } else if (cleanFound.startsWith("0")) {
                        cleanFound
                    } else if (cleanFound.startsWith("9") && cleanFound.length == 10) {
                        "0" + cleanFound
                    } else {
                        cleanFound
                    }
                    return contactPhotoMap[cleanNum] ?: contactPhotoMap[cleanNum.takeLast(10)]
                }
            }
        }
        return null
    }

    suspend fun getSmsThreads(showSpam: Boolean): List<SmsThread> = withContext(Dispatchers.IO) {
        val threadsList = mutableListOf<SmsThread>()
        
        val uri = Uri.parse("content://sms/")
        val projection = arrayOf("_id", "thread_id", "address", "body", "date", "read", "type", "status")

        try {
            context.contentResolver.query(uri, projection, null, null, "date DESC")?.use { cursor ->
                val idIdx = cursor.getColumnIndex("_id")
                val threadIdIdx = cursor.getColumnIndex("thread_id")
                val addressIdx = cursor.getColumnIndex("address")
                val bodyIdx = cursor.getColumnIndex("body")
                val dateIdx = cursor.getColumnIndex("date")
                val readIdx = cursor.getColumnIndex("read")
                val typeIdx = cursor.getColumnIndex("type")
                val statusIdx = cursor.getColumnIndex("status")

                val latestMessagePerThread = mutableMapOf<Long, SmsMessage>()
                val unreadCounts = mutableMapOf<Long, Int>()

                var count = 0
                while (cursor.moveToNext() && count < 1000) {
                    count++
                    val threadId = if (threadIdIdx != -1) cursor.getLong(threadIdIdx) else 0L
                    val address = if (addressIdx != -1) cursor.getString(addressIdx) ?: "Unknown" else "Unknown"
                    val body = if (bodyIdx != -1) cursor.getString(bodyIdx) ?: "" else ""
                    val date = if (dateIdx != -1) cursor.getLong(dateIdx) else System.currentTimeMillis()
                    val read = if (readIdx != -1) cursor.getInt(readIdx) else 1
                    val type = if (typeIdx != -1) cursor.getInt(typeIdx) else 1
                    val id = if (idIdx != -1) cursor.getLong(idIdx) else 0L
                    val status = if (statusIdx != -1) cursor.getInt(statusIdx) else -1

                    val processedBody = processMessageBodyForMissedCall(body)
                    val msg = SmsMessage(id = id, threadId = threadId, address = address, body = processedBody, date = date, type = type, read = read, status = status)
                    if (!latestMessagePerThread.containsKey(threadId)) {
                        latestMessagePerThread[threadId] = msg
                    }
                    if (read == 0 && type == 1) { // unread inbox message
                        unreadCounts[threadId] = (unreadCounts[threadId] ?: 0) + 1
                    }
                }

                for ((threadId, latestMsg) in latestMessagePerThread) {
                    val address = latestMsg.address
                    val body = latestMsg.body
                    val date = latestMsg.date
                    val unread = unreadCounts[threadId] ?: 0

                    val isMessageSpam = spamFilterEngine.isSpam(address, body, contactMap.keys)

                    if (isMessageSpam == showSpam) {
                        val name = getContactNameForMissedCall(address, body)
                        val photoUri = getContactPhotoUri(address, body)
                        threadsList.add(
                            SmsThread(
                                threadId = threadId,
                                address = address,
                                body = body, // already processed in latestMsg
                                date = date,
                                unreadCount = unread,
                                isSpam = isMessageSpam,
                                senderName = name,
                                photoUri = photoUri
                            )
                        )
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e("SmsRepository", "No SMS permissions", e)
        } catch (e: Exception) {
            Log.e("SmsRepository", "Error querying threads with primary method", e)
        }

        if (threadsList.isEmpty()) {
            // Fallback to older conversations URI query
            try {
                val olderUri = Uri.parse("content://sms/conversations")
                context.contentResolver.query(olderUri, null, null, null, null)?.use { cursor ->
                    val threadIdIdx = cursor.getColumnIndex("thread_id")
                    val snippetIdx = cursor.getColumnIndex("snippet")

                    if (threadIdIdx != -1) {
                        var countFallback = 0
                        while (cursor.moveToNext() && countFallback < 150) {
                            countFallback++
                            val threadId = cursor.getLong(threadIdIdx)
                            val body = if (snippetIdx != -1) cursor.getString(snippetIdx) ?: "" else ""
                            val processedBody = processMessageBodyForMissedCall(body)
                            val (address, date, unread) = getLatestMessageDetails(threadId)
                            val isMessageSpam = spamFilterEngine.isSpam(address, processedBody, contactMap.keys)
                            if (isMessageSpam == showSpam) {
                                val name = getContactNameForMissedCall(address, processedBody)
                                val photoUri = getContactPhotoUri(address, processedBody)
                                threadsList.add(
                                    SmsThread(
                                        threadId = threadId,
                                        address = address,
                                        body = processedBody,
                                        date = date,
                                        unreadCount = unread,
                                        isSpam = isMessageSpam,
                                        senderName = name,
                                        photoUri = photoUri
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (fallbackEx: Exception) {
                Log.e("SmsRepository", "Fallback query also failed", fallbackEx)
            }
        }

        // Extremely robust fallback: query inbox & sent directly and group by thread_id
        if (threadsList.isEmpty()) {
            val inboxUris = listOf(
                Uri.parse("content://sms/inbox"),
                Uri.parse("content://sms/sent")
            )
            val latestMessagePerThread = mutableMapOf<Long, SmsMessage>()
            val unreadCounts = mutableMapOf<Long, Int>()

            for (uri in inboxUris) {
                try {
                    context.contentResolver.query(uri, null, null, null, "date DESC")?.use { cursor ->
                        val idIdx = cursor.getColumnIndex("_id")
                        val threadIdIdx = cursor.getColumnIndex("thread_id")
                        val addressIdx = cursor.getColumnIndex("address")
                        val bodyIdx = cursor.getColumnIndex("body")
                        val dateIdx = cursor.getColumnIndex("date")
                        val readIdx = cursor.getColumnIndex("read")
                        val typeIdx = cursor.getColumnIndex("type")
                        val statusIdx = cursor.getColumnIndex("status")

                        var countInSent = 0
                        while (cursor.moveToNext() && countInSent < 1000) {
                            countInSent++
                            val threadId = if (threadIdIdx != -1) cursor.getLong(threadIdIdx) else 0L
                            val address = if (addressIdx != -1) cursor.getString(addressIdx) ?: "Unknown" else "Unknown"
                            val body = if (bodyIdx != -1) cursor.getString(bodyIdx) ?: "" else ""
                            val date = if (dateIdx != -1) cursor.getLong(dateIdx) else System.currentTimeMillis()
                            val read = if (readIdx != -1) cursor.getInt(readIdx) else 1
                            val type = if (typeIdx != -1) cursor.getInt(typeIdx) else 1
                            val id = if (idIdx != -1) cursor.getLong(idIdx) else 0L
                            val status = if (statusIdx != -1) cursor.getInt(statusIdx) else -1

                            val processedBody = processMessageBodyForMissedCall(body)
                            val msg = SmsMessage(id = id, threadId = threadId, address = address, body = processedBody, date = date, type = type, read = read, status = status)
                            if (!latestMessagePerThread.containsKey(threadId) || (latestMessagePerThread[threadId]?.date ?: 0L) < date) {
                                latestMessagePerThread[threadId] = msg
                            }
                            if (read == 0 && type == 1) { // unread inbox message
                                unreadCounts[threadId] = (unreadCounts[threadId] ?: 0) + 1
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SmsRepository", "Error querying fallback uri $uri", e)
                }
            }

            for ((threadId, latestMsg) in latestMessagePerThread) {
                val address = latestMsg.address
                val body = latestMsg.body
                val date = latestMsg.date
                val unread = unreadCounts[threadId] ?: 0

                val isMessageSpam = spamFilterEngine.isSpam(address, body, contactMap.keys)

                if (isMessageSpam == showSpam) {
                    val name = getContactNameForMissedCall(address, body)
                    val photoUri = getContactPhotoUri(address, body)
                    threadsList.add(
                        SmsThread(
                            threadId = threadId,
                            address = address,
                            body = body,
                            date = date,
                            unreadCount = unread,
                            isSpam = isMessageSpam,
                            senderName = name,
                            photoUri = photoUri
                        )
                    )
                }
            }
        }

        // Sort by date descending
        return@withContext threadsList.sortedByDescending { it.date }
    }

    private fun getLatestMessageDetails(threadId: Long): Triple<String, Long, Int> {
        val urisToTry = listOf(
            Uri.parse("content://sms/"),
            Uri.parse("content://sms/inbox"),
            Uri.parse("content://sms/sent")
        )
        val projection = arrayOf("address", "date", "read")
        val selection = "thread_id = ?"
        val selectionArgs = arrayOf(threadId.toString())
        val sortOrder = "date DESC"
        
        for (uri in urisToTry) {
            try {
                context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val addressIdx = cursor.getColumnIndex("address")
                        val dateIdx = cursor.getColumnIndex("date")
                        val readIdx = cursor.getColumnIndex("read")
                        
                        if (addressIdx != -1 && dateIdx != -1) {
                            val address = cursor.getString(addressIdx) ?: "Unknown"
                            val date = cursor.getLong(dateIdx)
                            
                            // Count unread messages in this thread
                            var unread = 0
                            if (readIdx != -1) {
                                do {
                                    val read = cursor.getInt(readIdx)
                                    if (read == 0) unread++
                                } while (cursor.moveToNext())
                            }
                            
                            return Triple(address, date, unread)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("SmsRepository", "Error fetching details from $uri for thread $threadId", e)
            }
        }
        
        return Triple("Unknown", System.currentTimeMillis(), 0)
    }

    suspend fun getMessagesForThread(threadId: Long): List<SmsMessage> = withContext(Dispatchers.IO) {
        val messages = mutableListOf<SmsMessage>()
        val uri = Uri.parse("content://sms/")
        val selection = "thread_id = ?"
        val selectionArgs = arrayOf(threadId.toString())
        val sortOrder = "date ASC" // Chronological order for conversations

        val projection = arrayOf("_id", "thread_id", "address", "body", "date", "type", "read", "status")

        try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idIdx = cursor.getColumnIndex("_id")
                val threadIdIdx = cursor.getColumnIndex("thread_id")
                val addressIdx = cursor.getColumnIndex("address")
                val bodyIdx = cursor.getColumnIndex("body")
                val dateIdx = cursor.getColumnIndex("date")
                val typeIdx = cursor.getColumnIndex("type")
                val readIdx = cursor.getColumnIndex("read")
                val statusIdx = cursor.getColumnIndex("status")

                while (cursor.moveToNext()) {
                    val id = if (idIdx != -1) cursor.getLong(idIdx) else 0L
                    val tId = if (threadIdIdx != -1) cursor.getLong(threadIdIdx) else threadId
                    val address = if (addressIdx != -1) cursor.getString(addressIdx) ?: "Unknown" else "Unknown"
                    val body = if (bodyIdx != -1) cursor.getString(bodyIdx) ?: "" else ""
                    val date = if (dateIdx != -1) cursor.getLong(dateIdx) else System.currentTimeMillis()
                    val type = if (typeIdx != -1) cursor.getInt(typeIdx) else 1
                    val read = if (readIdx != -1) cursor.getInt(readIdx) else 1
                    val status = if (statusIdx != -1) cursor.getInt(statusIdx) else -1

                    messages.add(
                        SmsMessage(id, tId, address, processMessageBodyForMissedCall(body), date, type, read, status)
                    )
                }
            }
            // Mark all received messages in this thread as read
            markThreadAsRead(threadId)
        } catch (e: SecurityException) {
            Log.e("SmsRepository", "No SMS permissions to fetch conversation", e)
        } catch (e: Exception) {
            Log.e("SmsRepository", "Error loading messages for thread $threadId", e)
        }

        if (messages.isEmpty()) {
            val inboxUris = listOf(
                Uri.parse("content://sms/inbox"),
                Uri.parse("content://sms/sent")
            )
            for (u in inboxUris) {
                try {
                    context.contentResolver.query(u, null, selection, selectionArgs, sortOrder)?.use { cursor ->
                        val idIdx = cursor.getColumnIndex("_id")
                        val threadIdIdx = cursor.getColumnIndex("thread_id")
                        val addressIdx = cursor.getColumnIndex("address")
                        val bodyIdx = cursor.getColumnIndex("body")
                        val dateIdx = cursor.getColumnIndex("date")
                        val typeIdx = cursor.getColumnIndex("type")
                        val readIdx = cursor.getColumnIndex("read")
                        val statusIdx = cursor.getColumnIndex("status")

                        while (cursor.moveToNext()) {
                            val id = if (idIdx != -1) cursor.getLong(idIdx) else 0L
                            val tId = if (threadIdIdx != -1) cursor.getLong(threadIdIdx) else threadId
                            val address = if (addressIdx != -1) cursor.getString(addressIdx) ?: "Unknown" else "Unknown"
                            val body = if (bodyIdx != -1) cursor.getString(bodyIdx) ?: "" else ""
                            val date = if (dateIdx != -1) cursor.getLong(dateIdx) else System.currentTimeMillis()
                            val type = if (typeIdx != -1) cursor.getInt(typeIdx) else 1
                            val read = if (readIdx != -1) cursor.getInt(readIdx) else 1
                            val status = if (statusIdx != -1) cursor.getInt(statusIdx) else -1

                            messages.add(
                                SmsMessage(id, tId, address, processMessageBodyForMissedCall(body), date, type, read, status)
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SmsRepository", "Error loading fallback messages from $u for thread $threadId", e)
                }
            }
            messages.sortBy { it.date }
        }
        return@withContext messages
    }

    private fun markThreadAsRead(threadId: Long) {
        val uri = Uri.parse("content://sms/inbox")
        val values = android.content.ContentValues().apply {
            put("read", 1)
        }
        val selection = "thread_id = ? AND read = 0"
        val selectionArgs = arrayOf(threadId.toString())
        try {
            context.contentResolver.update(uri, values, selection, selectionArgs)
        } catch (e: Exception) {
            Log.e("SmsRepository", "Failed to mark thread as read", e)
        }
    }

    fun getActiveSimSlots(): List<SimInfo> {
        val list = mutableListOf<SimInfo>()
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                    if (sm != null) {
                        val activeList = sm.activeSubscriptionInfoList
                        if (activeList != null) {
                            for (info in activeList) {
                                list.add(
                                    SimInfo(
                                        id = info.subscriptionId,
                                        slotIndex = info.simSlotIndex,
                                        displayName = info.displayName?.toString() ?: "SIM ${info.simSlotIndex + 1}",
                                        carrierName = info.carrierName?.toString() ?: ""
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("SmsRepository", "Error getting subscription info", e)
            }
        }
        // Fallback or default list to always allow switching SIMs visually/functionally
        if (list.isEmpty()) {
            list.add(SimInfo(id = 1, slotIndex = 0, displayName = "SIM 1", carrierName = "MCI"))
            list.add(SimInfo(id = 2, slotIndex = 1, displayName = "SIM 2", carrierName = "Irancell"))
        }
        return list
    }

    suspend fun sendSms(address: String, message: String, subId: Int? = null, threadId: Long? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val resolvedThreadId = threadId ?: try {
                Telephony.Threads.getOrCreateThreadId(context, address)
            } catch (e: Exception) {
                null
            }

            // Write to Sent box FIRST to get ID
            val sentUri = writeSmsToSentBox(address, message, resolvedThreadId)
            val messageId = try { sentUri?.lastPathSegment?.toLong() } catch (e: Exception) { null }

            val smsManager: SmsManager = if (subId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                @Suppress("DEPRECATION")
                SmsManager.getSmsManagerForSubscriptionId(subId)
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
            }

            val settings = com.example.data.NirvanaSettings(context)
            val isDeliveryReportEnabled = settings.isDeliveryReportEnabled

            val deliveryIntent: PendingIntent? = if (isDeliveryReportEnabled && messageId != null) {
                val intent = Intent("com.example.SMS_DELIVERED").apply {
                    putExtra("message_id", messageId)
                    `package` = context.packageName
                }
                PendingIntent.getBroadcast(
                    context,
                    messageId.toInt(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                null
            }

            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                val deliveryIntents = if (deliveryIntent != null) {
                    val list = ArrayList<PendingIntent>()
                    for (i in 0 until parts.size) {
                        list.add(deliveryIntent)
                    }
                    list
                } else null
                smsManager.sendMultipartTextMessage(address, null, parts, null, deliveryIntents)
            } else {
                smsManager.sendTextMessage(address, null, message, null, deliveryIntent)
            }
            
            return@withContext true
        } catch (e: Exception) {
            Log.e("SmsRepository", "Failed to send SMS to $address on SIM $subId", e)
            return@withContext false
        }
    }

    fun writeSmsToSentBox(address: String, body: String, threadId: Long? = null): Uri? {
        val uri = Uri.parse("content://sms/sent")
        val resolvedThreadId = threadId ?: try {
            Telephony.Threads.getOrCreateThreadId(context, address)
        } catch (e: Exception) {
            null
        }
        val values = android.content.ContentValues().apply {
            put("address", address)
            put("body", body)
            put("date", System.currentTimeMillis())
            put("read", 1)
            put("type", 2) // Sent
            if (resolvedThreadId != null) {
                put("thread_id", resolvedThreadId)
            }
        }
        return try {
            context.contentResolver.insert(uri, values)
        } catch (e: Exception) {
            Log.e("SmsRepository", "Failed to write sent SMS to ContentProvider", e)
            null
        }
    }

    fun writeSmsToInbox(address: String, body: String, threadId: Long? = null) {
        val uri = Uri.parse("content://sms/inbox")
        val resolvedThreadId = threadId ?: try {
            Telephony.Threads.getOrCreateThreadId(context, address)
        } catch (e: Exception) {
            null
        }
        val values = android.content.ContentValues().apply {
            put("address", address)
            put("body", body)
            put("date", System.currentTimeMillis())
            put("read", 0) // Unread
            put("type", 1) // Received
            if (resolvedThreadId != null) {
                put("thread_id", resolvedThreadId)
            }
        }
        try {
            context.contentResolver.insert(uri, values)
        } catch (e: Exception) {
            Log.e("SmsRepository", "Failed to write inbox SMS to ContentProvider", e)
        }
    }

    suspend fun deleteMessage(messageId: Long): Boolean = withContext(Dispatchers.IO) {
        val uri = Uri.parse("content://sms/$messageId")
        try {
            val rows = context.contentResolver.delete(uri, null, null)
            return@withContext rows > 0
        } catch (e: Exception) {
            Log.e("SmsRepository", "Failed to delete message $messageId", e)
            return@withContext false
        }
    }

    suspend fun deleteThread(threadId: Long): Boolean = withContext(Dispatchers.IO) {
        val uri = Uri.parse("content://sms/conversations/$threadId")
        try {
            val rows = context.contentResolver.delete(uri, null, null)
            return@withContext rows > 0
        } catch (e: Exception) {
            Log.e("SmsRepository", "Failed to delete thread $threadId", e)
            return@withContext false
        }
    }

    suspend fun getAllSmsThreads(): Pair<List<SmsThread>, List<SmsThread>> = withContext(Dispatchers.IO) {
        val regularThreads = mutableListOf<SmsThread>()
        val spamThreads = mutableListOf<SmsThread>()

        // Preload classifications and rules to avoid sequential DB hits
        val spamDb = SpamDatabase.getDatabase(context)
        val classificationsMap = try {
            spamDb.classificationDao().getAllClassifications().associateBy { it.sender }
        } catch (e: Exception) {
            emptyMap<String, com.example.data.database.MessageClassification>()
        }

        val databaseRules = try {
            spamDb.spamRuleDao().getAllRules()
        } catch (e: Exception) {
            emptyList<com.example.data.database.SpamRule>()
        }

        // Optimize: precompute normalized contact numbers for instant O(1) matching in the spam filter loop
        val normalizedContacts = contactMap.keys.map { normalizePhoneNumber(it) }.toSet()

        val uri = Uri.parse("content://sms/")
        val projection = arrayOf("_id", "thread_id", "address", "body", "date", "read", "type", "status")
        val latestMessagePerThread = mutableMapOf<Long, SmsMessage>()
        val unreadCounts = mutableMapOf<Long, Int>()

        try {
            // Fast 1: Pre-calculate unread counts in a fast indexed query
            context.contentResolver.query(
                Uri.parse("content://sms/"),
                arrayOf("thread_id"),
                "read = 0 AND type = 1",
                null,
                null
            )?.use { cursor ->
                val threadIdIdx = cursor.getColumnIndex("thread_id")
                if (threadIdIdx != -1) {
                    while (cursor.moveToNext()) {
                        val tId = cursor.getLong(threadIdIdx)
                        if (tId != 0L) {
                            unreadCounts[tId] = (unreadCounts[tId] ?: 0) + 1
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SmsRepository", "Error querying unread counts fast", e)
        }

        try {
            // Retrieve all messages sorted by date DESC. Skip processing if we already have the latest message for a thread.
            context.contentResolver.query(uri, projection, null, null, "date DESC")?.use { cursor ->
                val idIdx = cursor.getColumnIndex("_id")
                val threadIdIdx = cursor.getColumnIndex("thread_id")
                val addressIdx = cursor.getColumnIndex("address")
                val bodyIdx = cursor.getColumnIndex("body")
                val dateIdx = cursor.getColumnIndex("date")
                val readIdx = cursor.getColumnIndex("read")
                val typeIdx = cursor.getColumnIndex("type")
                val statusIdx = cursor.getColumnIndex("status")

                while (cursor.moveToNext()) {
                    val threadId = if (threadIdIdx != -1) cursor.getLong(threadIdIdx) else 0L
                    
                    // Fast skip: If we already captured the latest message for this thread, skip row processing immediately!
                    if (latestMessagePerThread.containsKey(threadId)) continue

                    val address = if (addressIdx != -1) cursor.getString(addressIdx) ?: "Unknown" else "Unknown"
                    val body = if (bodyIdx != -1) cursor.getString(bodyIdx) ?: "" else ""
                    val date = if (dateIdx != -1) cursor.getLong(dateIdx) else System.currentTimeMillis()
                    val read = if (readIdx != -1) cursor.getInt(readIdx) else 1
                    val type = if (typeIdx != -1) cursor.getInt(typeIdx) else 1
                    val id = if (idIdx != -1) cursor.getLong(idIdx) else 0L
                    val status = if (statusIdx != -1) cursor.getInt(statusIdx) else -1

                    val processedBody = processMessageBodyForMissedCall(body)
                    val msg = SmsMessage(id = id, threadId = threadId, address = address, body = processedBody, date = date, type = type, read = read, status = status)
                    latestMessagePerThread[threadId] = msg
                }
            }
        } catch (e: SecurityException) {
            Log.e("SmsRepository", "No SMS permissions", e)
        } catch (e: Exception) {
            Log.e("SmsRepository", "Error querying threads with primary method", e)
        }

        if (latestMessagePerThread.isEmpty()) {
            val inboxUris = listOf(
                Uri.parse("content://sms/inbox"),
                Uri.parse("content://sms/sent")
            )
            for (fallbackUri in inboxUris) {
                try {
                    context.contentResolver.query(fallbackUri, projection, null, null, "date DESC")?.use { cursor ->
                        val idIdx = cursor.getColumnIndex("_id")
                        val threadIdIdx = cursor.getColumnIndex("thread_id")
                        val addressIdx = cursor.getColumnIndex("address")
                        val bodyIdx = cursor.getColumnIndex("body")
                        val dateIdx = cursor.getColumnIndex("date")
                        val readIdx = cursor.getColumnIndex("read")
                        val typeIdx = cursor.getColumnIndex("type")
                        val statusIdx = cursor.getColumnIndex("status")

                        while (cursor.moveToNext()) {
                            val threadId = if (threadIdIdx != -1) cursor.getLong(threadIdIdx) else 0L
                            val address = if (addressIdx != -1) cursor.getString(addressIdx) ?: "Unknown" else "Unknown"
                            val body = if (bodyIdx != -1) cursor.getString(bodyIdx) ?: "" else ""
                            val date = if (dateIdx != -1) cursor.getLong(dateIdx) else System.currentTimeMillis()
                            val read = if (readIdx != -1) cursor.getInt(readIdx) else 1
                            val type = if (typeIdx != -1) cursor.getInt(typeIdx) else 1
                            val id = if (idIdx != -1) cursor.getLong(idIdx) else 0L
                            val status = if (statusIdx != -1) cursor.getInt(statusIdx) else -1

                            val processedBody = processMessageBodyForMissedCall(body)
                            val msg = SmsMessage(id = id, threadId = threadId, address = address, body = processedBody, date = date, type = type, read = read, status = status)
                            if (!latestMessagePerThread.containsKey(threadId) || (latestMessagePerThread[threadId]?.date ?: 0L) < date) {
                                latestMessagePerThread[threadId] = msg
                            }
                            if (read == 0 && type == 1) {
                                unreadCounts[threadId] = (unreadCounts[threadId] ?: 0) + 1
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SmsRepository", "Error querying fallback uri $fallbackUri", e)
                }
            }
        }

        for ((threadId, latestMsg) in latestMessagePerThread) {
            val address = latestMsg.address
            val body = latestMsg.body
            val date = latestMsg.date
            val unread = unreadCounts[threadId] ?: 0

            // Speed up: run fast in-memory spam classification using normalized contacts set
            val isMessageSpam = isSpamQuick(address, body, normalizedContacts, classificationsMap, databaseRules)
            val name = getContactNameForMissedCall(address, body)
            val photoUri = getContactPhotoUri(address, body)

            val threadItem = SmsThread(
                threadId = threadId,
                address = address,
                body = body,
                date = date,
                unreadCount = unread,
                isSpam = isMessageSpam,
                senderName = name,
                photoUri = photoUri
            )

            if (isMessageSpam) {
                spamThreads.add(threadItem)
            } else {
                regularThreads.add(threadItem)
            }
        }

        regularThreads.sortByDescending { it.date }
        spamThreads.sortByDescending { it.date }

        return@withContext Pair(regularThreads, spamThreads)
    }

    private fun isSpamQuick(
        sender: String,
        body: String,
        normalizedContacts: Set<String>,
        classifications: Map<String, com.example.data.database.MessageClassification>,
        databaseRules: List<com.example.data.database.SpamRule>
    ): Boolean {
        val cleanSender = normalizePhoneNumber(sender)
        val cached = classifications[sender] ?: classifications[cleanSender]

        // 1. User manual override takes absolute highest priority
        if (cached != null && cached.userOverridden) {
            return cached.isSpam
        }

        // 1.5. Bank check: Banking messages are NEVER classified as spam automatically (unless manually overridden above)
        if (isBankMessage(sender, body)) {
            return false
        }

        // 2. Safe check: If the sender is in contacts, it's not spam (unless manually overridden above)
        if (normalizedContacts.contains(cleanSender) || (cleanSender.length >= 10 && normalizedContacts.contains(cleanSender.takeLast(10)))) {
            return false
        }

        // 3. Otherwise use the automatically cached classification
        if (cached != null) {
            return cached.isSpam
        }

        val isWhitelisted = databaseRules.filter { !it.isBlacklist }.any { rule ->
            when (rule.type) {
                "SENDER" -> sender.contains(rule.pattern) || normalizePhoneNumber(sender) == normalizePhoneNumber(rule.pattern)
                "KEYWORD" -> body.contains(rule.pattern, ignoreCase = true)
                else -> false
            }
        }
        if (isWhitelisted) {
            return false
        }

        val isBlacklisted = databaseRules.filter { it.isBlacklist }.any { rule ->
            when (rule.type) {
                "SENDER" -> sender.startsWith(rule.pattern) || normalizePhoneNumber(sender) == normalizePhoneNumber(rule.pattern)
                "KEYWORD" -> body.contains(rule.pattern, ignoreCase = true)
                else -> false
            }
        }
        if (isBlacklisted) {
            return true
        }

        val defaultSpamKeywords = setOf(
            "تور", "برنده", "قرعه", "کشی", "قرعه‌کشی", "شارژ", "استخدام", "رایگان", 
            "ثبت نام", "خرید", "تخفیف", "ویژه", "درآمد", "پیشنهاد", "لغو", "پشتیبانی",
            "کسب و کار", "سرمایه", "فروش", "جشنواره", "رزرو", "هدیه", "جایزه", "وام",
            "سود", "بورس", "فیلتر", "ارسال عدد", "ارسال پیامک", "کد تخفیف", "آفورد",
            "تبلیغاتی", "تبلیغات", "پیامک تبلیغاتی", "off", "winner", "promo", "free",
            "lottery", "gift", "discount", "cashback", "bonus", "prize"
        )
        val defaultSpamSenderPrefixes = listOf(
            "1000", "2000", "3000", "5000", "9000", "021", "026", "981000", "982000", "983000", "985000"
        )

        val hasUnsubscribeKeyword = body.contains("لغو") || body.contains("عدد") || body.contains("off", ignoreCase = true)
        val isShortCode = sender.length <= 8 || defaultSpamSenderPrefixes.any { sender.startsWith(it) }

        if (isShortCode && hasUnsubscribeKeyword) {
            return true
        }

        val hasKeyword = defaultSpamKeywords.any { body.contains(it, ignoreCase = true) }
        if (isShortCode && hasKeyword) {
            return true
        }

        return false
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
                                  body.contains("مبلغ") || 
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
