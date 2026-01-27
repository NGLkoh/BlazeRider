package com.aorv.blazerider

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
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
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.*
import com.google.firebase.storage.FirebaseStorage
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

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
    private var recipientContact: Contact? = null

    private val PICK_FILE_REQUEST = 1001
    private val CAPTURE_IMAGE_REQUEST = 1002
    private val PICK_IMAGE_REQUEST = 1003
    private var photoUri: Uri? = null

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
        Glide.with(this).asGif().load(R.drawable.typing_indicator).into(binding.typingIndicator)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
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
    }

    private fun handleIntentData(currentUid: String) {
        try {
            chatId = intent.getStringExtra("chatId")
            recipientContact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getSerializableExtra("CONTACT", Contact::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getSerializableExtra("CONTACT") as? Contact
            }

            // REVERTED: Instead of a random ID, generate a stable ID based on both users
            if (recipientContact != null && chatId == null) {
                val partnerId = recipientContact!!.id
                chatId = if (currentUid < partnerId) "${currentUid}_$partnerId" else "${partnerId}_$currentUid"
            }

            val targetId = chatId ?: return

            messageAdapter = MessageAdapter(currentUid, targetId, db)
            binding.messagesRecyclerView.adapter = messageAdapter
            binding.messagesRecyclerView.layoutManager = LinearLayoutManager(this).apply {
                stackFromEnd = true
            }

            if (recipientContact != null) {
                val contact = recipientContact!!
                binding.userName.text = "${contact.firstName} ${contact.lastName}".trim()
                loadRecipientProfile(contact.id)
            } else {
                loadChatDetails(targetId)
            }

            listenForMessages(targetId)
            listenForTypingStatus(targetId, currentUid)
            markChatAsRead()

        } catch (e: Exception) {
            Log.e("ChatCrash", "Intent data error: ${e.message}")
            finish()
        }
    }

    private fun loadRecipientProfile(uid: String) {
        db.collection("users").document(uid).get().addOnSuccessListener { userDoc ->
            if (userDoc.exists()) {
                val firstName = userDoc.getString("firstName") ?: ""
                val lastName = userDoc.getString("lastName") ?: ""
                binding.userName.text = "$firstName $lastName".trim()
                val imageUrl = userDoc.getString("profileImageUrl")
                Glide.with(this)
                    .load(imageUrl)
                    .placeholder(R.drawable.ic_anonymous)
                    .error(R.drawable.ic_anonymous)
                    .circleCrop()
                    .into(binding.userImage)
            }
        }
    }

    private fun initializeChatOnFirstMessage() {
        val currentUid = auth.currentUser?.uid ?: return
        val contact = recipientContact ?: return
        val id = chatId ?: return

        val chatData = hashMapOf(
            "chatId" to id,
            "members" to listOf(currentUid, contact.id),
            "type" to "p2p",
            "createdAt" to FieldValue.serverTimestamp(),
            "typing" to mapOf(currentUid to false, contact.id to false)
        )

        db.collection("chats").document(id).set(chatData, SetOptions.merge())
            .addOnSuccessListener {
                initializeUserChatEntry(currentUid, id)
                initializeUserChatEntry(contact.id, id)
                recipientContact = null
            }
    }

    private fun initializeUserChatEntry(userId: String, chatId: String) {
        val userChatRef = db.collection("userChats").document(userId).collection("chats").document(chatId)
        val data = hashMapOf(
            "chatId" to chatId,
            "type" to "p2p",
            "unreadCount" to 0
        )
        userChatRef.set(data, SetOptions.merge())
    }

    private fun sendMessage() {
        val text = binding.messageInput.text.toString().trim()
        val currentUid = auth.currentUser?.uid ?: return
        val id = chatId ?: return

        if (text.isNotEmpty()) {
            if (recipientContact != null) {
                initializeChatOnFirstMessage()
            }

            val messageData = hashMapOf(
                "senderId" to currentUid,
                "content" to text,
                "timestamp" to FieldValue.serverTimestamp(),
                "type" to "text",
                "status" to "sent",
                "readBy" to listOf(currentUid),
                "deletedBy" to emptyList<String>()
            )

            binding.messageInput.text.clear()

            db.collection("chats").document(id)
                .collection("messages").add(messageData)
                .addOnSuccessListener { docRef ->
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

        val batch = db.batch()
        val chatRef = db.collection("chats").document(chatId)

        batch.update(chatRef, "lastMessage", lastMessageData)

        chatRef.get().addOnSuccessListener { doc ->
            if (!doc.exists()) return@addOnSuccessListener

            val members = doc.get("members") as? List<*>
            members?.forEach { memberId ->
                val uid = memberId.toString()
                val userChatRef = db.collection("userChats").document(uid).collection("chats").document(chatId)

                // Archive/Restore Logic: set(merge) makes the chat reappears in the list
                // for both users as soon as a new message is sent.
                batch.set(userChatRef, mapOf("lastMessage" to lastMessageData), SetOptions.merge())

                if (uid != senderId) {
                    batch.update(userChatRef, "unreadCount", FieldValue.increment(1))
                }
            }
            batch.commit()
        }
    }

    private fun markChatAsRead() {
        val currentUid = auth.currentUser?.uid ?: return
        val id = chatId ?: return
        db.collection("userChats").document(currentUid).collection("chats").document(id)
            .update("unreadCount", 0)
            .addOnFailureListener { Log.e("ChatRead", "Error marking read") }
    }

    private fun loadChatDetails(chatId: String) {
        db.collection("chats").document(chatId).get().addOnSuccessListener { doc ->
            val members = doc.get("members") as? List<*>
            val otherUserId = members?.find { it != auth.currentUser?.uid } as? String
            otherUserId?.let { uid -> loadRecipientProfile(uid) }
        }
    }

    private fun updateTypingStatus(status: Boolean) {
        val currentUid = auth.currentUser?.uid ?: return
        val id = chatId ?: return
        db.collection("chats").document(id).update("typing.$currentUid", status)
    }

    private fun listenForTypingStatus(id: String, currentUid: String) {
        typingListener?.remove()
        typingListener = db.collection("chats").document(id).addSnapshotListener { snapshot, _ ->
            val typingMap = snapshot?.get("typing") as? Map<*, *> ?: emptyMap<String, Any>()
            val otherIsTyping = typingMap.any { (u, t) -> u != currentUid && t == true }
            binding.typingIndicator.isVisible = otherIsTyping
        }
    }

    private fun listenForMessages(targetChatId: String) {
        val currentUid = auth.currentUser?.uid ?: return
        messageListener?.remove()
        messageListener = db.collection("chats").document(targetChatId)
            .collection("messages").orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, e ->
                if (e != null) return@addSnapshotListener
                snapshot?.let { querySnapshot ->
                    val messages = querySnapshot.toObjects(Message::class.java)
                    // The "Archive" effect: This hides messages you previously 'deleted'
                    val filteredMessages = messages.filter { !it.deletedBy.contains(currentUid) }
                    messageAdapter.submitList(filteredMessages)

                    if (filteredMessages.isNotEmpty()) {
                        binding.messagesRecyclerView.post {
                            binding.messagesRecyclerView.scrollToPosition(filteredMessages.size - 1)
                        }
                    }
                }
            }
    }

    private fun listenForNotifications() {
        val userId = auth.currentUser?.uid ?: return
        notificationsListener = db.collection("users").document(userId).collection("notifications")
            .orderBy("createdAt", Query.Direction.DESCENDING).limit(1)
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null || snapshot.isEmpty) return@addSnapshotListener
                val doc = snapshot.documents[0]
                if (doc.getString("entityId") == chatId) return@addSnapshotListener
                if (!(doc.getBoolean("isRead") ?: true)) {
                    showNotificationBanner(doc.getString("title") ?: "New Message", doc.getString("message") ?: "")
                }
            }
    }

    private fun showNotificationBanner(title: String, message: String) {
        mainHandler.post {
            binding.notificationBanner.notificationTitle.text = title
            binding.notificationBanner.notificationMessage.text = message
            binding.notificationBanner.root.visibility = View.VISIBLE
            mainHandler.postDelayed({ binding.notificationBanner.root.visibility = View.GONE }, 5000)
        }
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

        if (recipientContact != null) {
            initializeChatOnFirstMessage()
        }

        val storageRef = FirebaseStorage.getInstance().reference.child("chat_files/$id/${UUID.randomUUID()}")
        storageRef.putFile(uri).addOnSuccessListener {
            storageRef.downloadUrl.addOnSuccessListener { downloadUri ->
                val messageData = hashMapOf(
                    "senderId" to currentUid,
                    "timestamp" to FieldValue.serverTimestamp(),
                    "type" to type,
                    "content" to downloadUri.toString(),
                    "status" to "sent",
                    "deletedBy" to emptyList<String>()
                )
                db.collection("chats").document(id).collection("messages").add(messageData)
                    .addOnSuccessListener { docRef ->
                        updateLastMessage(id, if (type == "image") "Sent an image" else "Sent a file", currentUid, docRef.id)
                    }
            }
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
        startActivityForResult(intent, PICK_FILE_REQUEST)
    }

    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
            return
        }

        val photoFile: File? = try { createImageFile() } catch (e: IOException) { null }
        photoFile?.also {
            photoUri = FileProvider.getUriForFile(this, "com.aorv.blazerider.fileprovider", it)
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivityForResult(intent, CAPTURE_IMAGE_REQUEST)
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        startActivityForResult(intent, PICK_IMAGE_REQUEST)
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File.createTempFile("JPEG_${timeStamp}_", ".jpg", getExternalFilesDir(Environment.DIRECTORY_PICTURES))
    }

    override fun onDestroy() {
        super.onDestroy()
        messageListener?.remove()
        typingListener?.remove()
        notificationsListener?.remove()
    }
}