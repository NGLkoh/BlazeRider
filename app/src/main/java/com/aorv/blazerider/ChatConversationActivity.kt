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
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aorv.blazerider.databinding.ActivityChatConversationBinding
import com.google.android.material.imageview.ShapeableImageView
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
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import androidx.cardview.widget.CardView
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

class ChatConversationActivity : AppCompatActivity() {
    private lateinit var binding: ActivityChatConversationBinding
    private lateinit var db: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private lateinit var functions: FirebaseFunctions
    private lateinit var userNameTextView: TextView
    private lateinit var userImageView: ShapeableImageView
    private lateinit var callButton: ImageButton
    private lateinit var infoButton: ImageButton
    private lateinit var messageInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var messagesRecyclerView: RecyclerView
    private lateinit var typingIndicator: ImageView
    private lateinit var messageAdapter: MessageAdapter
    private var messageListener: ListenerRegistration? = null
    private var typingListener: ListenerRegistration? = null
    private var notificationsListener: ListenerRegistration? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var chatId: String? = null
    private val PICK_FILE_REQUEST = 1001
    private val CAPTURE_IMAGE_REQUEST = 1002
    private val PICK_IMAGE_REQUEST = 1003
    private var photoUri: Uri? = null
    private val STORAGE_PERMISSION_REQUEST = 1004
    private val CAMERA_PERMISSION_REQUEST = 1005
    private val GALLERY_PERMISSION_REQUEST = 1006
    private var isTyping = false

    // Notification Banner
    private lateinit var notificationBanner: CardView
    private lateinit var notificationTitleBanner: TextView
    private lateinit var notificationMessageBanner: TextView
    private lateinit var notificationDismissBanner: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityChatConversationBinding.inflate(layoutInflater)
            setContentView(binding.root)
        } catch (e: Exception) {
            setContentView(R.layout.activity_chat_conversation)
        }

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        functions = FirebaseFunctions.getInstance()

        userNameTextView = findViewById(R.id.user_name) ?: run {
            Log.e("ChatConversationActivity", "user_name TextView not found")
            Toast.makeText(this, "UI error: user_name not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        userImageView = findViewById(R.id.user_image) ?: run {
            Log.e("ChatConversationActivity", "user_image ImageView not found")
            Toast.makeText(this, "UI error: user_image not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        messagesRecyclerView = findViewById(R.id.messages_recycler_view)
        messageInput = findViewById(R.id.message_input)
        sendButton = findViewById(R.id.send_button)
        callButton = findViewById(R.id.call_button)
        infoButton = findViewById(R.id.info_button)
        typingIndicator = findViewById(R.id.typing_indicator) ?: run {
            Log.e("ChatConversationActivity", "typing_indicator ImageView not found")
            Toast.makeText(this, "UI error: typing_indicator not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val backButton = findViewById<ImageButton>(R.id.back_button)
        val fileButton = findViewById<ImageButton>(R.id.file_button)
        val cameraButton = findViewById<ImageButton>(R.id.camera_button)
        val galleryButton = findViewById<ImageButton>(R.id.gallery_button)

        // Banner setup
        notificationBanner = findViewById(R.id.notification_banner)
        notificationTitleBanner = notificationBanner.findViewById(R.id.notification_title)
        notificationMessageBanner = notificationBanner.findViewById(R.id.notification_message)
        notificationDismissBanner = notificationBanner.findViewById(R.id.notification_dismiss)

        Glide.with(this)
            .asGif()
            .load(R.drawable.typing_indicator)
            .into(typingIndicator)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })

        backButton.setOnClickListener { finish() }
        sendButton.setOnClickListener {
            sendMessage()
            if (isTyping) {
                isTyping = false
                updateTypingStatus(false)
            }
        }
        fileButton.setOnClickListener { openFilePicker() }
        cameraButton.setOnClickListener { openCamera() }
        galleryButton.setOnClickListener { openGallery() }

        messageInput.addTextChangedListener(object : TextWatcher {
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

        chatId = intent.getStringExtra("chatId")
        val contact = intent.getSerializableExtra("CONTACT") as? Contact
        val currentUid = auth.currentUser?.uid ?: ""

        messageAdapter = MessageAdapter(currentUid, chatId ?: "", db)
        messagesRecyclerView.adapter = messageAdapter
        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = true
        messagesRecyclerView.layoutManager = layoutManager

        if (contact != null) {
            val fullName = "${contact.firstName} ${contact.lastName}".trim().ifEmpty { "Unknown User" }
            userNameTextView.text = fullName
            Glide.with(this)
                .load(contact.profileImageUrl)
                .placeholder(R.drawable.ic_anonymous)
                .error(R.drawable.ic_anonymous)
                .into(userImageView)
            if (chatId != null) {
                markChatAsRead()
                listenForMessages(chatId!!)
                listenForTypingStatus(chatId!!, currentUid, fullName)
            } else {
                findOrCreateP2PChat(contact.id)
            }
        } else if (chatId != null) {
            markChatAsRead()
            loadChatDetails(chatId!!)
            listenForMessages(chatId!!)
            listenForTypingStatus(chatId!!, currentUid, "Group Chat")
        } else {
            Log.e("ChatConversationActivity", "No contact or chatId provided")
            Toast.makeText(this, "Error: No contact or chat selected", Toast.LENGTH_SHORT).show()
            finish()
        }

        listenForNotifications()
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
                if (e != null) return@addSnapshotListener
                if (snapshot != null && snapshot.metadata.hasPendingWrites()) return@addSnapshotListener

                if (snapshot != null && !snapshot.isEmpty) {
                    val doc = snapshot.documents[0]
                    val notificationId = doc.id
                    val type = doc.getString("type")
                    val entityId = doc.getString("entityId")
                    
                    // Don't show notification for the chat we are currently in
                    if (type == "message" && entityId == chatId) return@addSnapshotListener

                    if (!doc.getBoolean("isRead")!! && !prefs.getBoolean(notificationId, false)) {
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
            notificationTitleBanner.text = title
            notificationMessageBanner.text = message
            notificationBanner.visibility = View.VISIBLE

            notificationBanner.setOnClickListener {
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
                notificationBanner.visibility = View.GONE
            }

            notificationDismissBanner.setOnClickListener { notificationBanner.visibility = View.GONE }
            mainHandler.removeCallbacksAndMessages("banner_timeout")
            mainHandler.postAtTime({
                if (!isFinishing && !isDestroyed) notificationBanner.visibility = View.GONE
            }, "banner_timeout", SystemClock.uptimeMillis() + 5000)
        }
    }

    private fun findOrCreateP2PChat(otherUserId: String) {
        val currentUid = auth.currentUser?.uid ?: return
        val potentialChatId = if (currentUid > otherUserId) "${currentUid}_${otherUserId}" else "${otherUserId}_${currentUid}"

        db.collection("chats").document(potentialChatId).get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    chatId = potentialChatId
                    markChatAsRead()
                    listenForMessages(chatId!!)
                    listenForTypingStatus(chatId!!, currentUid, userNameTextView.text.toString())
                } else {
                    createNewP2PChat(otherUserId)
                }
            }
            .addOnFailureListener { e ->
                Log.e("ChatConversationActivity", "Error finding chat", e)
                createNewP2PChat(otherUserId) // Fallback to creating a new chat
            }
    }

    private fun updateTypingStatus(isTyping: Boolean) {
        val currentUid = auth.currentUser?.uid ?: return
        if (chatId == null) return

        db.collection("chats").document(chatId!!)
            .update("typing.$currentUid", isTyping)
            .addOnFailureListener { e ->
                Log.e("ChatConversation", "Failed to update typing status", e)
            }
    }

    private fun listenForTypingStatus(chatId: String, currentUid: String, displayName: String) {
        typingListener = db.collection("chats").document(chatId)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e("ChatConversation", "Error listening for typing status", e)
                    return@addSnapshotListener
                }

                snapshot?.let {
                    val typingMap = it.get("typing") as? Map<String, Boolean> ?: emptyMap()
                    val isSomeoneTyping = typingMap.any { (userId, isTyping) ->
                        userId != currentUid && isTyping
                    }
                    typingIndicator.isVisible = isSomeoneTyping
                }
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
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).also { intent ->
            val photoFile: File? = try {
                createImageFile()
            } catch (e: IOException) {
                null
            }
            photoFile?.also {
                photoUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", it)
                intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                startActivityForResult(intent, CAPTURE_IMAGE_REQUEST)
            }
        }
    }

    private fun openGallery() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), GALLERY_PERMISSION_REQUEST)
            return
        }
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"
        }
        try {
            startActivityForResult(Intent.createChooser(intent, "Select Image"), PICK_IMAGE_REQUEST)
        } catch (e: Exception) {
            Toast.makeText(this, "No gallery app found", Toast.LENGTH_SHORT).show()
        }
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile("JPEG_${timeStamp}_", ".jpg", storageDir)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return

        when (requestCode) {
            PICK_FILE_REQUEST -> data?.data?.let { uploadFileToFirebase(it) }
            CAPTURE_IMAGE_REQUEST -> photoUri?.let { showPhotoPreview(it) }
            PICK_IMAGE_REQUEST -> data?.data?.let { showPhotoPreview(it) }
        }
    }

    private fun showPhotoPreview(uri: Uri) {
        val intent = Intent(this, PhotoPreviewActivity::class.java).apply {
            putExtra("PHOTO_URI", uri.toString())
            putExtra("CHAT_ID", chatId)
        }
        startActivity(intent)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
            return
        }
        when (requestCode) {
            STORAGE_PERMISSION_REQUEST -> openFilePicker()
            CAMERA_PERMISSION_REQUEST -> openCamera()
            GALLERY_PERMISSION_REQUEST -> openGallery()
        }
    }

    private fun uploadFileToFirebase(uri: Uri) {
        val fileRef = FirebaseStorage.getInstance().reference.child("chat_files/${chatId}/${UUID.randomUUID()}")
        fileRef.putFile(uri)
            .addOnSuccessListener {
                fileRef.downloadUrl.addOnSuccessListener { downloadUri ->
                    sendFileMessage(downloadUri.toString(), "file")
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to upload file", Toast.LENGTH_SHORT).show()
            }
    }

    private fun sendFileMessage(fileUrl: String, type: String) {
        val currentUid = auth.currentUser?.uid ?: return
        val messageId = UUID.randomUUID().toString()
        val timestamp = Timestamp.now()

        val message = Message(
            id = messageId,
            senderId = currentUid,
            content = fileUrl,
            timestamp = timestamp,
            type = type,
            status = "sent",
            readBy = listOf(currentUid)
        )

        db.collection("chats").document(chatId!!).collection("messages").document(messageId).set(message)
            .addOnSuccessListener {
                updateLastMessageAndUnreadCount(message, messageId)
            }
    }

    private fun sendMessage() {
        val content = messageInput.text.toString().trim()
        if (content.isEmpty() || chatId == null) return

        val currentUid = auth.currentUser?.uid ?: return
        val messageId = UUID.randomUUID().toString()
        val timestamp = Timestamp.now()

        messageInput.text.clear()

        val message = Message(
            id = messageId,
            senderId = currentUid,
            content = content,
            timestamp = timestamp,
            type = "text",
            status = "sent",
            readBy = listOf(currentUid)
        )

        db.collection("chats").document(chatId!!).collection("messages").document(messageId).set(message)
            .addOnSuccessListener {
                updateLastMessageAndUnreadCount(message, messageId)
                createChatNotifications(content)
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to send message", Toast.LENGTH_SHORT).show()
            }
    }

    private fun createChatNotifications(content: String) {
        val currentUid = auth.currentUser?.uid ?: return
        if (chatId == null) return

        db.collection("chats").document(chatId!!).get().addOnSuccessListener { chatDoc ->
            val membersMap = chatDoc.get("members") as? Map<String, Any> ?: return@addOnSuccessListener
            val recipientIds = membersMap.keys.filter { it != currentUid }

            db.collection("users").document(currentUid).get().addOnSuccessListener { userDoc ->
                val firstName = userDoc.getString("firstName") ?: ""
                val lastName = userDoc.getString("lastName") ?: ""
                val senderName = "$firstName $lastName".trim()

                recipientIds.forEach { recipientId ->
                    val notification = hashMapOf(
                        "actorId" to currentUid,
                        "entityId" to chatId,
                        "entityType" to "chat",
                        "message" to "$senderName: $content",
                        "type" to "message",
                        "createdAt" to FieldValue.serverTimestamp(),
                        "isRead" to false,
                        "metadata" to emptyMap<String, Any>()
                    )

                    db.collection("users").document(recipientId)
                        .collection("notifications")
                        .add(notification)
                }
            }
        }
    }

    private fun updateLastMessageAndUnreadCount(message: Message, messageId: String) {
        val lastMessageData = mapOf(
            "id" to messageId,
            "content" to if (message.type == "text") message.content else "Attachment",
            "senderId" to message.senderId,
            "timestamp" to message.timestamp
        )

        db.collection("chats").document(chatId!!).update("lastMessage", lastMessageData)

        db.collection("chats").document(chatId!!).get().addOnSuccessListener { chatDoc ->
            val members = chatDoc.get("members") as? Map<String, Any> ?: return@addOnSuccessListener
            members.keys.forEach { userId ->
                val userChatRef = db.collection("userChats").document(userId).collection("chats").document(chatId!!)
                db.runTransaction { transaction ->
                    val snapshot = transaction.get(userChatRef)
                    val newUnreadCount = if (userId == auth.currentUser?.uid) 0 else (snapshot.getLong("unreadCount") ?: 0) + 1
                    transaction.set(userChatRef, mapOf("lastMessage" to lastMessageData, "unreadCount" to newUnreadCount), SetOptions.merge())
                }.addOnFailureListener {
                    // Handle potential transaction failure
                }
            }
        }
    }

    private fun loadChatDetails(chatId: String) {
        db.collection("chats").document(chatId).get()
            .addOnSuccessListener { document ->
                if (!document.exists()) return@addOnSuccessListener
                val type = document.getString("type")
                if (type == "p2p") {
                    val members = document.get("members") as? Map<String, Any>
                    val otherUserId = members?.keys?.firstOrNull { it != auth.currentUser?.uid }
                    otherUserId?.let { userId ->
                        db.collection("users").document(userId).get().addOnSuccessListener { userDoc ->
                            val name = "${userDoc.getString("firstName")} ${userDoc.getString("lastName")}".trim()
                            userNameTextView.text = name
                            Glide.with(this).load(userDoc.getString("profileImageUrl")).placeholder(R.drawable.ic_anonymous).into(userImageView)
                        }
                    }
                } else {
                    userNameTextView.text = document.getString("name")
                    Glide.with(this).load(document.getString("groupImage")).placeholder(R.drawable.ic_anonymous).into(userImageView)
                }
            }
    }

    private fun createNewP2PChat(otherUserId: String) {
        val currentUid = auth.currentUser?.uid ?: return
        // A more robust way to generate a p2p chat ID
        chatId = if (currentUid > otherUserId) "${currentUid}_${otherUserId}" else "${otherUserId}_${currentUid}"

        val timestamp = Timestamp.now()
        val chatData = mapOf(
            "type" to "p2p",
            "createdAt" to timestamp,
            "memberIds" to listOf(currentUid, otherUserId),
            "members" to mapOf(
                currentUid to mapOf("joinedAt" to timestamp, "role" to "member"),
                otherUserId to mapOf("joinedAt" to timestamp, "role" to "member")
            ),
            "typing" to mapOf(currentUid to false, otherUserId to false),
            "lastMessage" to null
        )

        db.collection("chats").document(chatId!!).set(chatData, SetOptions.merge())
            .addOnSuccessListener {
                val userChatData = mapOf("lastMessage" to null, "unreadCount" to 0, "deleted" to false)
                db.collection("userChats").document(currentUid).collection("chats").document(chatId!!).set(userChatData)
                db.collection("userChats").document(otherUserId).collection("chats").document(chatId!!).set(userChatData)

                listenForMessages(chatId!!)
                listenForTypingStatus(chatId!!, currentUid, userNameTextView.text.toString())
            }
    }

    private fun listenForMessages(chatId: String) {
        messageListener = db.collection("chats").document(chatId)
            .collection("messages")
            .orderBy("timestamp")
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    return@addSnapshotListener
                }
                
                // Reset unreadCount whenever new messages are received while the user is in the chat
                if (snapshot != null && !snapshot.isEmpty) {
                    markChatAsRead()
                }

                snapshot?.let {
                    val messages = it.documents.mapNotNull { doc ->
                        val message = doc.toObject(Message::class.java)?.copy(id = doc.id)
                        message
                    }
                    messageAdapter.submitList(messages)
                    messagesRecyclerView.scrollToPosition(messageAdapter.itemCount - 1)
                }
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        messageListener?.remove()
        typingListener?.remove()
        notificationsListener?.remove()
        mainHandler.removeCallbacksAndMessages(null)
    }
}