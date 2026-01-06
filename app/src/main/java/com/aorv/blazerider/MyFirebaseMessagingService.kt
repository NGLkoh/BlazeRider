package com.aorv.blazerider

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MyFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        const val NEW_MESSAGE_ACTION = "com.aorv.blazerider.NEW_MESSAGE"
        const val EXTRA_CHAT_ID = "chatId"
        const val EXTRA_MESSAGE_CONTENT = "messageContent"
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d("FCM", "Message received: ${remoteMessage.messageId}")

        // Handle notification payload
        remoteMessage.notification?.let {
            val title = it.title ?: "New Message"
            val body = it.body ?: "You have a new message"
            val chatId = remoteMessage.data["chatId"]

            // Save notification to database
            val notificationDao = AppDatabase.getDatabase(applicationContext).notificationDao()
            val notificationEntity = NotificationEntity(title = title, body = body, chatId = chatId)
            CoroutineScope(Dispatchers.IO).launch {
                notificationDao.insert(notificationEntity)
            }


            // Send notification to system tray
            sendNotification(title, body, chatId)

            // Send local broadcast for in-app handling
            val intent = Intent(NEW_MESSAGE_ACTION).apply {
                putExtra(EXTRA_CHAT_ID, chatId)
                putExtra(EXTRA_MESSAGE_CONTENT, body)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            Log.d("FCM", "Local broadcast sent for chatId: $chatId")
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("FCM", "New token: $token")
        // Update the user's FCM token in Firestore
        val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        val userId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        userId?.let {
            db.collection("users").document(it)
                .update("fcmToken", token)
                .addOnSuccessListener {
                    Log.d("FCM", "FCM token updated in Firestore")
                }
                .addOnFailureListener { e ->
                    Log.e("FCM", "Failed to update FCM token", e)
                }
        }
    }

    private fun sendNotification(title: String, messageBody: String, chatId: String?) {
        val channelId = "chat_notifications"
        val notificationId = System.currentTimeMillis().toInt()

        val intent = Intent(this, ChatConversationActivity::class.java).apply {
            putExtra("chatId", chatId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_blaze_rider) // Replace with your app's notification icon
            .setContentTitle(title)
            .setContentText(messageBody)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create notification channel for Android O and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Chat Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for new chat messages"
            }
            notificationManager.createNotificationChannel(channel)
        }

        notificationManager.notify(notificationId, notificationBuilder.build())
        Log.d("FCM", "Notification displayed: $title - $messageBody")
    }
}
