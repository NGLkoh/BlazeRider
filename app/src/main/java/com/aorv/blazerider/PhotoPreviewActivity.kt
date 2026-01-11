package com.aorv.blazerider

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import java.util.UUID

class PhotoPreviewActivity : AppCompatActivity() {
    private lateinit var db: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private lateinit var photoImageView: ImageView
    private lateinit var sendButton: Button
    private lateinit var closeButton: ImageButton
    private lateinit var loadingGif: ImageView
    private var photoUri: Uri? = null
    private var chatId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_preview)

        db = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()

        photoImageView = findViewById(R.id.photo_preview)
        sendButton = findViewById(R.id.send_photo_button)
        closeButton = findViewById(R.id.close_button)
        loadingGif = findViewById(R.id.loading_gif)

        photoUri = Uri.parse(intent.getStringExtra("PHOTO_URI") ?: "")
        chatId = intent.getStringExtra("CHAT_ID")

        Glide.with(this)
            .load(photoUri)
            .into(photoImageView)

        closeButton.setOnClickListener { finish() }

        sendButton.setOnClickListener { uploadPhotoToFirebase() }
    }

    private fun uploadPhotoToFirebase() {
        photoUri?.let { uri ->
            sendButton.text = "Uploading..."
            sendButton.isEnabled = false
            loadingGif.visibility = View.VISIBLE
            Glide.with(this).asGif().load(R.drawable.ic_loading_gif).into(loadingGif)

            val storage = FirebaseStorage.getInstance()
            val photoRef = storage.reference.child("chat_photos/${chatId}/${UUID.randomUUID()}.jpg")
            photoRef.putFile(uri)
                .addOnSuccessListener {
                    photoRef.downloadUrl.addOnSuccessListener { downloadUri ->
                        sendPhotoMessage(downloadUri.toString())
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("PhotoPreview", "Failed to upload photo", e)
                    Toast.makeText(this, "Upload failed", Toast.LENGTH_SHORT).show()
                    resetUI()
                }
        }
    }

    private fun sendPhotoMessage(photoUrl: String) {
        val currentUid = auth.currentUser?.uid ?: return
        val messageId = UUID.randomUUID().toString()
        val timestamp = Timestamp.now()

        val message = Message(
            id = messageId,
            senderId = currentUid,
            content = photoUrl,
            timestamp = timestamp,
            type = "image",
            status = "sent",
            readBy = listOf(currentUid)
        )

        db.collection("chats").document(chatId!!).collection("messages").document(messageId).set(message)
            .addOnSuccessListener {
                updateLastMessageAndNotifications(photoUrl, currentUid, timestamp)
                finish()
            }
            .addOnFailureListener { e ->
                Log.e("PhotoPreview", "Failed to send message", e)
                resetUI()
            }
    }

    private fun updateLastMessageAndNotifications(photoUrl: String, senderId: String, timestamp: Timestamp) {
        val lastMessageData = mapOf(
            "content" to "Photo",
            "senderId" to senderId,
            "timestamp" to timestamp
        )

        db.collection("chats").document(chatId!!).update("lastMessage", lastMessageData)

        // Trigger notifications and update unread counts
        db.collection("chats").document(chatId!!).get().addOnSuccessListener { chatDoc ->
            val members = chatDoc.get("members") as? Map<String, Any> ?: return@addOnSuccessListener
            val recipientIds = members.keys.filter { it != senderId }

            members.keys.forEach { userId ->
                val userChatRef = db.collection("userChats").document(userId).collection("chats").document(chatId!!)
                db.runTransaction { transaction ->
                    val snapshot = transaction.get(userChatRef)
                    val newUnreadCount = if (userId == senderId) 0 else (snapshot.getLong("unreadCount") ?: 0) + 1
                    transaction.update(userChatRef, mapOf("lastMessage" to lastMessageData, "unreadCount" to newUnreadCount))
                }
            }

            // Create notification for recipients
            db.collection("users").document(senderId).get().addOnSuccessListener { userDoc ->
                val senderName = "${userDoc.getString("firstName")} ${userDoc.getString("lastName")}".trim()
                recipientIds.forEach { recipientId ->
                    val notification = hashMapOf(
                        "actorId" to senderId,
                        "entityId" to chatId,
                        "entityType" to "chat",
                        "message" to "$senderName sent a photo",
                        "type" to "message",
                        "createdAt" to FieldValue.serverTimestamp(),
                        "isRead" to false,
                        "metadata" to emptyMap<String, Any>()
                    )
                    db.collection("users").document(recipientId).collection("notifications").add(notification)
                }
            }
        }
    }

    private fun resetUI() {
        sendButton.text = "Send"
        sendButton.isEnabled = true
        loadingGif.visibility = View.GONE
    }
}
