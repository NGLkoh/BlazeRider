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

        // Get data from intent
        photoUri = Uri.parse(intent.getStringExtra("PHOTO_URI") ?: "")
        chatId = intent.getStringExtra("CHAT_ID")

        // Load photo into ImageView
        Glide.with(this)
            .load(photoUri)
            .into(photoImageView)

        // Close button
        closeButton.setOnClickListener {
            finish()
        }

        // Send button
        sendButton.setOnClickListener {
            uploadPhotoToFirebase()
        }
    }

    private fun uploadPhotoToFirebase() {
        photoUri?.let { uri ->
            // Update button state to show uploading
            sendButton.text = "Uploading please wait..."
            sendButton.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.darker_gray)
            sendButton.isEnabled = false // Disable to prevent duplicate clicks

            // Show and load animated GIF
            loadingGif.visibility = View.VISIBLE
            Glide.with(this)
                .asGif()
                .load(R.drawable.ic_loading_gif)
                .into(loadingGif)

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
                    Toast.makeText(this, "Failed to upload photo", Toast.LENGTH_SHORT).show()
                    // Revert button state on failure
                    sendButton.text = "Send"
                    sendButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.red_orange)
                    sendButton.isEnabled = true
                    loadingGif.visibility = View.GONE
                }
        } ?: run {
            Toast.makeText(this, "No photo selected", Toast.LENGTH_SHORT).show()
            // Revert button state if no URI
            sendButton.text = "Send"
            sendButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.red_orange)
            sendButton.isEnabled = true
            loadingGif.visibility = View.GONE
        }
    }

    private fun sendPhotoMessage(photoUrl: String) {
        val currentUid = auth.currentUser?.uid ?: return
        val messageId = UUID.randomUUID().toString()
        val timestamp = Timestamp.now()

        val messageData = hashMapOf(
            "senderId" to currentUid,
            "content" to photoUrl,
            "timestamp" to timestamp,
            "type" to "image",
            "readBy" to listOf(currentUid)
        )

        db.collection("chats").document(chatId!!).collection("messages")
            .document(messageId).set(messageData)
            .addOnSuccessListener {
                val lastMessage = hashMapOf(
                    "content" to "Photo",
                    "senderId" to currentUid,
                    "timestamp" to timestamp
                )
                db.collection("chats").document(chatId!!)
                    .update("lastMessage", lastMessage)

                db.collection("chats").document(chatId!!).get()
                    .addOnSuccessListener { document ->
                        val members = document.get("members") as? Map<String, Map<String, Any>> ?: return@addOnSuccessListener
                        members.keys.forEach { userId ->
                            db.collection("userChats").document(userId)
                                .collection("chats").document(chatId!!)
                                .get()
                                .addOnSuccessListener { userChatDoc ->
                                    val currentUnreadCount = userChatDoc.getLong("unreadCount") ?: 0L
                                    val updateData = hashMapOf(
                                        "lastMessage" to lastMessage,
                                        "unreadCount" to if (userId == currentUid) 0 else currentUnreadCount + 1
                                    )
                                    db.collection("userChats").document(userId)
                                        .collection("chats").document(chatId!!)
                                        .set(updateData)
                                }
                        }
                    }
                loadingGif.visibility = View.GONE
                finish() // Close activity on success
            }
            .addOnFailureListener { e ->
                Log.e("PhotoPreview", "Failed to send photo message", e)
                Toast.makeText(this, "Failed to send photo message", Toast.LENGTH_SHORT).show()
                // Revert button state on failure
                sendButton.text = "Send"
                sendButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.red_orange)
                sendButton.isEnabled = true
                loadingGif.visibility = View.GONE
            }
    }
}