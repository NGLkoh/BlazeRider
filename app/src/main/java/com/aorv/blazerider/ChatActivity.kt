package com.aorv.blazerider

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore

class ChatActivity : AppCompatActivity() {

    private lateinit var db: FirebaseFirestore
    private lateinit var chatId: String
    private lateinit var currentUserId: String
    private lateinit var typingIndicator: TextView
    private lateinit var messageInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        db = FirebaseFirestore.getInstance()

        // Replace with your actual intent/session data logic
        chatId = intent.getStringExtra("CHAT_ID") ?: ""
        currentUserId = intent.getStringExtra("USER_ID") ?: ""


        setupReadListener()
        setupTypingSystem()
    }

    /**
     * 1. MARK AS READ: Listen for new messages and mark them read if they are from the other user.
     */
    private fun setupReadListener() {
        if (chatId.isEmpty()) return

        db.collection("chats").document(chatId).collection("messages")
            .addSnapshotListener { snapshots, e ->
                if (e != null || snapshots == null) return@addSnapshotListener

                val batch = db.batch()
                var hasUpdates = false

                for (doc in snapshots.documents) {
                    val senderId = doc.getString("senderId")
                    val status = doc.getString("status")

                    // If message is from the other person and still marked as "sent"
                    if (senderId != currentUserId && status == "sent") {
                        batch.update(doc.reference, "status", "read")
                        hasUpdates = true
                    }
                }

                if (hasUpdates) {
                    batch.commit()
                }
            }
    }

    /**
     * 2. TYPING INDICATOR: Update your status and listen for the other user.
     */
    private fun setupTypingSystem() {
        if (chatId.isEmpty()) return

        // Update MY typing status based on text input
        messageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val isTyping = !s.isNullOrBlank()
                db.collection("chats").document(chatId)
                    .update("typing.$currentUserId", isTyping)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // LISTEN for the OTHER person's status
        db.collection("chats").document(chatId)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null && snapshot.exists()) {
                    val typingMap = snapshot.get("typing") as? Map<String, Boolean>
                    val members = snapshot.get("members") as? List<String>

                    // Identify the other user's ID
                    val otherUserId = members?.firstOrNull { it != currentUserId }

                    val isOtherTyping = typingMap?.get(otherUserId) ?: false

                    typingIndicator.visibility = if (isOtherTyping) View.VISIBLE else View.GONE
                    typingIndicator.text = "Typing..."
                }
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Ensure you stop showing as "typing" when leaving the screen
        if (chatId.isNotEmpty()) {
            db.collection("chats").document(chatId).update("typing.$currentUserId", false)
        }
    }
}