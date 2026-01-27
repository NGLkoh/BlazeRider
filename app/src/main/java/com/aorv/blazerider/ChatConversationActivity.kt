package com.aorv.blazerider

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.aorv.blazerider.databinding.ActivityChatConversationBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.bumptech.glide.Glide
import com.google.firebase.Timestamp
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

class ChatConversationActivity : AppCompatActivity() {
    private lateinit var binding: ActivityChatConversationBinding
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private lateinit var messageAdapter: MessageAdapter
    private var messageListener: ListenerRegistration? = null
    private var typingListener: ListenerRegistration? = null
    private var notificationsListener: ListenerRegistration? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var chatId: String? = null
    private var isTyping = false

    private val PICK_FILE_REQUEST = 1001
    private val CAPTURE_IMAGE_REQUEST = 1002
    private val PICK_IMAGE_REQUEST = 1003
    private var photoUri: Uri? = null
    private val STORAGE_PERMISSION_REQUEST = 1004
    private val CAMERA_PERMISSION_REQUEST = 1005
    private val GALLERY_PERMISSION_REQUEST = 1006

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatConversationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val currentUid = auth.currentUser?.uid
        if (currentUid == null) {
            Toast.makeText(this, "Session expired", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupUI()
        handleIntentData(currentUid)
        listenForNotifications()
    }

    private fun setupUI() {
        Glide.with(this)
            .asGif()
            .load(R.drawable.typing_indicator)
            .into(binding.typingIndicator)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })

        binding.backButton.setOnClickListener { finish() }
        binding.sendButton.setOnClickListener {
            sendMessage()
            if (isTyping) {
                isTyping = false
                updateTypingStatus(false)
            }
        }
        binding.fileButton.setOnClickListener { openFilePicker() }
        binding.cameraButton.setOnClickListener { openCamera() }
        binding.galleryButton.setOnClickListener { openGallery() }

        binding.messageInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val isCurrentlyTyping = !s.isNullOrEmpty()
                if (isCurrentlyTyping != isTyping && chatId != null) {
                    isTyping = isCurrentlyTyping
                    updateTypingStatus(isTyping)
                }
            }
        })

        binding.notificationBanner.notificationDismiss.setOnClickListener {
            binding.notificationBanner.root.visibility = View.GONE
        }
    }

    private fun handleIntentData(currentUid: String) {
        chatId = intent.getStringExtra("chatId")
        val contact = intent.getSerializableExtra("CONTACT") as? Contact

        messageAdapter = MessageAdapter(currentUid, chatId ?: "", db)
        binding.messagesRecyclerView.adapter = messageAdapter
        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = true
        binding.messagesRecyclerView.layoutManager = layoutManager

        if (contact != null) {
            val fullName = "${contact.firstName} ${contact.lastName}".trim().ifEmpty { "Unknown User" }
            binding.userName.text = fullName
            Glide.with(this)
                .load(contact.profileImageUrl)
                .placeholder(R.drawable.ic_anonymous)
                .error(R.drawable.ic_anonymous)
                .into(binding.userImage)
            
            findOrCreateP2PChat(contact)
        } else if (chatId != null) {
            markChatAsRead()
            loadChatDetails(chatId!!)
            listenForMessages(chatId!!)
            listenForTypingStatus(chatId!!, currentUid)
        } else {
            Toast.makeText(this, "Error: No chat selected", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun markChatAsRead() {
        val currentUid = auth.currentUser?.uid ?: return
        val id = chatId ?: return

        db.collection("userChats").document(currentUid)
            .collection("chats").document(id)
            .update("unreadCount", 0)
            .addOnFailureListener { e ->
                Log.e("ChatConversation", "Failed to reset unreadCount", e)
            }
    }

    private fun listenForNotifications() {
        val userId = auth.currentUser?.uid ?: return
        val prefs = getSharedPreferences("shown_notifications", MODE_PRIVATE)

        notificationsListener = db.collection("users").document(userId).collection("notifications")
            .orderBy("createdAt", Query.Direction.DESCENDING).limit(1)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null || snapshot.metadata.hasPendingWrites()) return@addSnapshotListener

                if (!snapshot.isEmpty) {
                    val doc = snapshot.documents[0]
                    val notificationId = doc.id
                    val type = doc.getString("type")
                    val entityId = doc.getString("entityId")
                    
                    if (type == "message" && entityId == chatId) return@addSnapshotListener

                    val isRead = doc.getBoolean("isRead") ?: true
                    if (!isRead && !prefs.getBoolean(notificationId, false)) {
                        val title = when (type) {
                            "reaction" -> "New Reaction"
                            "comment" -> "New Comment"
                            "message" -> "New Message"
                            else -> "New Notification"
                        }
                        val message = doc.getString("message") ?: ""
                        showNotificationBanner(title, message, notificationId, type, entityId)
                        prefs.edit().putBoolean(notificationId, true).apply()
                    }
                }
            }
    }

    private fun showNotificationBanner(title: String, message: String, notificationId: String, type: String?, entityId: String?) {
        mainHandler.post {
            if (isFinishing || isDestroyed) return@post
            binding.notificationBanner.notificationTitle.text = title
            binding.notificationBanner.notificationMessage.text = message
            binding.notificationBanner.root.visibility = View.VISIBLE

            binding.notificationBanner.root.setOnClickListener {
                val userId = auth.currentUser?.uid
                if (userId != null) {
                    db.collection("users").document(userId)
                        .collection("notifications").document(notificationId)
                        .update("isRead", true)
                }

                when (type) {
                    "reaction" -> {
                        val intent = Intent(this, SinglePostActivity::class.java).apply { putExtra("POST_ID", entityId) }
                        startActivity(intent)
                    }
                    "comment" -> {
                        val intent = Intent(this, CommentsActivity::class.java).apply { putExtra("POST_ID", entityId) }
                        startActivity(intent)
                    }
                    "message" -> {
                        if (entityId != chatId) {
                            val intent = Intent(this, ChatConversationActivity::class.java).apply { putExtra("chatId", entityId) }
                            startActivity(intent)
                        }
                    }
                }
                binding.notificationBanner.root.visibility = View.GONE
            }

            mainHandler.removeCallbacksAndMessages("banner_timeout")
            mainHandler.postAtTime({
                if (!isFinishing && !isDestroyed) binding.notificationBanner.root.visibility = View.GONE
            }, "banner_timeout", SystemClock.uptimeMillis() + 5000)
        }
    }

    private fun findOrCreateP2PChat(contact: Contact) {
        val currentUid = auth.currentUser?.uid ?: return
        val otherUserId = contact.id
        val potentialChatId = if (currentUid > otherUserId) "${currentUid}_${otherUserId}" else "${otherUserId}_${currentUid}"

        chatId = potentialChatId
        messageAdapter.setChatId(potentialChatId)

        val chatData = hashMapOf(
            "name" to "${contact.firstName} ${contact.lastName}".trim(),
            "members" to listOf(currentUid, otherUserId),
            "type" to "p2p",
            "typing" to mapOf(currentUid to false, otherUserId to false)
        )

        // 1. Ensure the main chat document exists
        db.collection("chats").document(potentialChatId).set(chatData, SetOptions.merge())
            .addOnSuccessListener {
                // 2. Initialize the chat for BOTH users in their personal 'userChats' collection
                // This prevents errors when trying to update 'unreadCount' later
                initializeUserChatEntry(currentUid, potentialChatId, contact)
                initializeUserChatEntry(otherUserId, potentialChatId, null) // Null because we'll fetch details later or use current user info

                markChatAsRead()
                listenForMessages(potentialChatId)
                listenForTypingStatus(potentialChatId, currentUid)
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to start chat: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun initializeUserChatEntry(userId: String, chatId: String, contact: Contact?) {
        val userChatRef = db.collection("userChats").document(userId).collection("chats").document(chatId)
        val data = hashMapOf(
            "chatId" to chatId,
            "type" to "p2p"
        )
        // Use merge to avoid overwriting existing unread counts or last messages
        userChatRef.set(data, SetOptions.merge())
    }
    private fun updateTypingStatus(isTyping: Boolean) {
        val currentUid = auth.currentUser?.uid ?: return
        val id = chatId ?: return

        db.collection("chats").document(id)
            .update("typing.$currentUid", isTyping)
            .addOnFailureListener { e ->
                Log.e("ChatConversation", "Failed to update typing status", e)
            }
    }

    private fun listenForTypingStatus(chatId: String, currentUid: String) {
        typingListener?.remove()
        typingListener = db.collection("chats").document(chatId)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener

                val typingMap = snapshot.get("typing") as? Map<*, *> ?: emptyMap<String, Any>()
                val isSomeoneTyping = typingMap.any { (userId, isTyping) ->
                    userId != currentUid && isTyping == true
                }
                binding.typingIndicator.isVisible = isSomeoneTyping
            }
    }

    private fun openFilePicker() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), STORAGE_PERMISSION_REQUEST)
            return
        }
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(Intent.createChooser(intent, "Select a File"), PICK_FILE_REQUEST)
        } catch (e: Exception) {
            Toast.makeText(this, "No file manager found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openCamera() {
        val permissions = mutableListOf<String>().apply {
            if (ContextCompat.checkSelfPermission(this@ChatConversationActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && ContextCompat.checkSelfPermission(this@ChatConversationActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), CAMERA_PERMISSION_REQUEST)
            return
        }
        
        val photoFile: File? = try {
            createImageFile()
        } catch (ex: IOException) {
            Toast.makeText(this, "Error creating file", Toast.LENGTH_SHORT).show()
            null
        }

        photoFile?.also {
            photoUri = FileProvider.getUriForFile(this, "com.aorv.blazerider.fileprovider", it)
            val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
            }
            startActivityForResult(takePictureIntent, CAPTURE_IMAGE_REQUEST)
        }
    }

    private fun openGallery() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), GALLERY_PERMISSION_REQUEST)
            return
        }
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        startActivityForResult(intent, PICK_IMAGE_REQUEST)
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile("JPEG_${timeStamp}_", ".jpg", storageDir)
    }

    private fun sendMessage() {
        val text = binding.messageInput.text.toString().trim()
        val currentUid = auth.currentUser?.uid ?: return
        val id = chatId ?: return

        if (text.isNotEmpty()) {
            val messageData = hashMapOf(
                "senderId" to currentUid,
                "content" to text,
                "timestamp" to FieldValue.serverTimestamp(),
                "type" to "text",
                "status" to "sent",         // Match your class default
                "readBy" to emptyList<String>(),    // Add this
                "deletedBy" to emptyList<String>()
            )

            db.collection("chats").document(id)
                .collection("messages").add(messageData)
                .addOnSuccessListener { docRef ->
                    binding.messageInput.text.clear()
                    updateLastMessage(id, text, currentUid, docRef.id)
                }
        }
    }

    private fun updateLastMessage(chatId: String, text: String, senderId: String, messageId: String) {
        val lastMessageData = hashMapOf(
            "id" to messageId,
            "content" to text,
            "senderId" to senderId,
            "timestamp" to FieldValue.serverTimestamp()
        )

        val chatRef = db.collection("chats").document(chatId)

        chatRef.get().addOnSuccessListener { doc ->
            if (!doc.exists()) return@addOnSuccessListener

            val batch = db.batch()

            // Update main chat object
            batch.update(chatRef, "lastMessage", lastMessageData)

            val members = doc.get("members") as? List<*>
            members?.forEach { memberId ->
                val uid = memberId.toString()
                val userChatRef = db.collection("userChats").document(uid).collection("chats").document(chatId)

                // USE SET with MERGE instead of UPDATE here.
                // If the document doesn't exist, UPDATE will crash. SET will create it.
                batch.set(userChatRef, mapOf("lastMessage" to lastMessageData), SetOptions.merge())

                if (uid != senderId) {
                    batch.update(userChatRef, "unreadCount", FieldValue.increment(1))
                }
            }

            batch.commit().addOnFailureListener { e ->
                Log.e("ChatConversation", "Batch failed: ${e.message}")
            }
        }
    }

    private fun listenForMessages(chatId: String) {
        val currentUid = auth.currentUser?.uid ?: return
        messageListener?.remove()
        messageListener = db.collection("chats").document(chatId)
            .collection("messages").orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.w("ChatConversation", "Listen failed.", e)
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val messages = snapshot.toObjects(Message::class.java)
                    // Filter out messages that have been deleted by the current user
                    val filteredMessages = messages.filter { !it.deletedBy.contains(currentUid) }
                    messageAdapter.submitList(filteredMessages)
                    if (filteredMessages.isNotEmpty()) {
                        binding.messagesRecyclerView.scrollToPosition(filteredMessages.size - 1)
                    }
                }
            }
    }

    private fun loadChatDetails(chatId: String) {
        db.collection("chats").document(chatId).get().addOnSuccessListener { doc ->
            val members = doc.get("members") as? List<*>
            val otherUserId = members?.find { it != auth.currentUser?.uid } as? String
            if (otherUserId != null) {
                db.collection("users").document(otherUserId).get().addOnSuccessListener { userDoc ->
                    val firstName = userDoc.getString("firstName") ?: ""
                    val lastName = userDoc.getString("lastName") ?: ""
                    val profileImageUrl = userDoc.getString("profileImageUrl")
                    binding.userName.text = "$firstName $lastName".trim()
                    Glide.with(this)
                        .load(profileImageUrl)
                        .placeholder(R.drawable.ic_anonymous)
                        .error(R.drawable.ic_anonymous)
                        .into(binding.userImage)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        messageListener?.remove()
        typingListener?.remove()
        notificationsListener?.remove()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            when (requestCode) {
                PICK_FILE_REQUEST -> data?.data?.let { uploadFile(it, "file") }
                CAPTURE_IMAGE_REQUEST -> photoUri?.let { uploadFile(it, "image") }
                PICK_IMAGE_REQUEST -> data?.data?.let { uploadFile(it, "image") }
            }
        }
    }

    private fun uploadFile(uri: Uri, type: String) {
        val currentUid = auth.currentUser?.uid ?: return
        val id = chatId ?: return
        val storageRef = FirebaseStorage.getInstance().reference
            .child("chat_files/$id/${UUID.randomUUID()}")

        storageRef.putFile(uri).addOnSuccessListener {
            storageRef.downloadUrl.addOnSuccessListener { downloadUri ->
                val messageData = hashMapOf(
                    "senderId" to currentUid,
                    "timestamp" to FieldValue.serverTimestamp(),
                    "type" to type,
                    "content" to downloadUri.toString(),
                    "deletedBy" to emptyList<String>()
                )
                db.collection("chats").document(id).collection("messages").add(messageData)
                    .addOnSuccessListener { docRef ->
                        updateLastMessage(id, if (type == "image") "Sent an image" else "Sent a file", currentUid, docRef.id)
                    }
            }
        }.addOnFailureListener {
            Toast.makeText(this, "Upload failed", Toast.LENGTH_SHORT).show()
        }
    }
}
