
package com.aorv.blazerider

import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        // Handle FCM messages here.
        println("From: ${remoteMessage.from}")

        // Check if message contains a data payload for the in-app banner.
        remoteMessage.data.isNotEmpty().let {
            println("Message data payload: " + remoteMessage.data)
            broadcastNewMessage(remoteMessage.data)
        }
    }

    private fun broadcastNewMessage(data: Map<String, String>) {
        val chatId = data["chatId"]
        val message = data["message"]
        val senderName = data["senderName"]

        val intent = Intent(NEW_MESSAGE_ACTION).apply {
            putExtra(EXTRA_CHAT_ID, chatId)
            putExtra(EXTRA_MESSAGE_CONTENT, "$senderName: $message")
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    companion object {
        const val NEW_MESSAGE_ACTION = "com.aorv.blazerider.NEW_MESSAGE"
        const val EXTRA_CHAT_ID = "extra_chat_id"
        const val EXTRA_MESSAGE_CONTENT = "extra_message_content"
    }
}
