package com.aorv.blazerider

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aorv.blazerider.databinding.ActivityMessagesBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.Timestamp
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

class MessagesActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMessagesBinding
    private lateinit var adapter: ChatThreadAdapter
    private val db = FirebaseFirestore.getInstance()
    private val chats = mutableListOf<Chat>()
    private val currentUserId = FirebaseAuth.getInstance().currentUser?.uid
    private var chatListener: ListenerRegistration? = null
    private lateinit var messageReceiver: BroadcastReceiver
    private var unreadFilterEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMessagesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Check if user is logged in
        if (currentUserId == null) {
            Toast.makeText(this, "Please log in to view messages", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Set up toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        // Handle back button click
        binding.btnBack.setOnClickListener {
            finish()
        }

        // Handle compose button click to launch NewChatActivity
        binding.btnCompose.setOnClickListener {
            startActivity(Intent(this, NewChatActivity::class.java))
        }

        // Set up RecyclerView
        adapter = ChatThreadAdapter { chat ->
            val intent = Intent(this, ChatConversationActivity::class.java)
            intent.putExtra("chatId", chat.chatId)
            if (chat.type == "p2p" && chat.contact != null) {
                Log.d("MessagesActivity", "Passing contact to ChatConversationActivity: ${chat.contact}")
                intent.putExtra("CONTACT", chat.contact)
            } else if (chat.type == "p2p") {
                Log.w("MessagesActivity", "No contact found for p2p chat: ${chat.chatId}")
            }
            startActivity(intent)
        }
        binding.messagesRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.messagesRecyclerView.adapter = adapter

        // Handle search input
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterChats(s.toString())
            }
        })

        // Handle unread button click
        binding.btnUnread.setOnClickListener {
            unreadFilterEnabled = !unreadFilterEnabled
            filterChats(binding.searchInput.text.toString())
        }

        // Set up local broadcast receiver for new messages
        setupMessageReceiver()

        // Add swipe-to-delete functionality
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                return false // We don't want to handle move gestures
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val chat = adapter.getChatAt(position)
                showDeleteConfirmationDialog(chat)
            }
        })
        itemTouchHelper.attachToRecyclerView(binding.messagesRecyclerView)
    }

    override fun onResume() {
        super.onResume()
        fetchChats()
    }

    override fun onPause() {
        super.onPause()
        chatListener?.remove()
    }

    private fun showDeleteConfirmationDialog(chat: Chat) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Chat")
            .setMessage("Are you sure you want to delete this chat?")
            .setNegativeButton("Cancel") { dialog, _ ->
                adapter.notifyItemChanged(chats.indexOf(chat)) // Revert the swipe
                dialog.dismiss()
            }
            .setPositiveButton("Delete") { _, _ ->
                deleteChat(chat)
            }
            .show()
    }

    private fun deleteChat(chat: Chat) {
        if (currentUserId == null) return

        lifecycleScope.launch {
            try {
                // Remove the current user from the chat's members list
                db.collection("chats").document(chat.chatId)
                    .update("members.$currentUserId", null)
                    .await()

                // Remove the chat from the user's chat list
                db.collection("userChats").document(currentUserId)
                    .collection("chats").document(chat.chatId)
                    .delete()
                    .await()

                // Remove the chat from the local list and update the adapter
                val index = chats.indexOf(chat)
                if (index != -1) {
                    chats.removeAt(index)
                    adapter.updateChats(chats)
                }

                Toast.makeText(this@MessagesActivity, "Chat deleted", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("MessagesActivity", "Error deleting chat: ${e.message}", e)
                Toast.makeText(this@MessagesActivity, "Failed to delete chat", Toast.LENGTH_SHORT).show()
                // If deletion fails, revert the UI change
                adapter.notifyItemChanged(chats.indexOf(chat))
            }
        }
    }

    private fun setupMessageReceiver() {
        messageReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val chatId = intent.getStringExtra(MyFirebaseMessagingService.EXTRA_CHAT_ID)
                val messageContent = intent.getStringExtra(MyFirebaseMessagingService.EXTRA_MESSAGE_CONTENT)
                if (chatId != null && messageContent != null) {
                    // Show an in-app notification (e.g., Toast)
                    Toast.makeText(
                        this@MessagesActivity,
                        "New message in chat $chatId: $messageContent",
                        Toast.LENGTH_SHORT
                    ).show()
                    Log.d("MessagesActivity", "Received new message for chat: $chatId, content: $messageContent")
                    // Note: Firestore listener in fetchChats() updates the chat list
                } else {
                    Log.w("MessagesActivity", "Invalid broadcast data: chatId=$chatId, messageContent=$messageContent")
                }
            }
        }
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(messageReceiver, IntentFilter(MyFirebaseMessagingService.NEW_MESSAGE_ACTION))
    }

    private fun fetchChats() {
        if (currentUserId == null) {
            Log.e("MessagesActivity", "Current user ID is null")
            return
        }

        Log.d("MessagesActivity", "Fetching chats for user: $currentUserId")

        chatListener = db.collection("chats")
            .whereNotEqualTo("members.$currentUserId", null)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Toast.makeText(this, "Failed to load chats: ${error.message}", Toast.LENGTH_SHORT).show()
                    Log.e("MessagesActivity", "Firestore error: ${error.message}", error)
                    return@addSnapshotListener
                }

                if (snapshot == null || snapshot.isEmpty) {
                    Log.d("MessagesActivity", "No chats found for user: $currentUserId")
                    chats.clear()
                    adapter.updateChats(chats)
                    return@addSnapshotListener
                }

                Log.d("MessagesActivity", "Found ${snapshot.documents.size} chat documents")

                lifecycleScope.launch {
                    val chatMap = mutableMapOf<String, Chat>()

                    snapshot.documents.forEach { document ->
                        try {
                            val chatId = document.id
                            if (chatMap.containsKey(chatId)) {
                                Log.d("MessagesActivity", "Skipping duplicate chat: $chatId")
                                return@forEach
                            }

                            val type = document.getString("type") ?: "group"
                            val lastMessageMap = document.get("lastMessage") as? Map<*, *>
                            val lastMessage = lastMessageMap?.get("content") as? String ?: ""
                            val lastMessageTimestamp = lastMessageMap?.get("timestamp") as? Timestamp
                                ?: document.getTimestamp("createdAt") ?: Timestamp.now()

                            Log.d("MessagesActivity", "Processing chat: $chatId, Type: $type")

                            val userChatDoc = db.collection("userChats")
                                .document(currentUserId)
                                .collection("chats")
                                .document(chatId)
                                .get()
                                .await()
                            val userLastMessageMap = userChatDoc.get("lastMessage") as? Map<*, *>
                            val userLastMessage = (userLastMessageMap?.get("content") as? String)?.takeIf { it.isNotEmpty() } ?: lastMessage
                            val userLastMessageTimestamp = userLastMessageMap?.get("timestamp") as? Timestamp
                                ?: lastMessageTimestamp

                            val unreadCount = userChatDoc.getLong("unreadCount")?.toInt() ?: 0

                            when (type) {
                                "p2p" -> {
                                    val members = document.get("members") as? Map<String, Any> ?: emptyMap()
                                    Log.d("MessagesActivity", "Members: $members")
                                    val otherUserId = members.keys.firstOrNull { it != currentUserId }
                                    Log.d("MessagesActivity", "Other user ID: $otherUserId")
                                    if (otherUserId != null) {
                                        Log.d("MessagesActivity", "Fetching user details for: $otherUserId")
                                        val userDoc = db.collection("users").document(otherUserId).get().await()
                                        if (userDoc.exists()) {
                                            val firstName = userDoc.getString("firstName") ?: ""
                                            val lastName = userDoc.getString("lastName") ?: ""
                                            val profileImage = userDoc.getString("profileImageUrl")
                                            val email = userDoc.getString("email") ?: ""
                                            val lastActive = when (val value = userDoc.get("lastActive")) {
                                                is Timestamp -> SimpleDateFormat(
                                                    "yyyy-MM-dd HH:mm:ss",
                                                    Locale.getDefault()
                                                ).format(value.toDate())
                                                is String -> value
                                                else -> null
                                            }
                                            val contact = Contact(
                                                id = otherUserId,
                                                firstName = firstName,
                                                lastName = lastName,
                                                profileImageUrl = profileImage,
                                                email = email,
                                                lastActive = lastActive
                                            )
                                            chatMap[chatId] = Chat(
                                                chatId = chatId,
                                                name = "$firstName $lastName".trim().ifEmpty { "Unknown User" },
                                                type = type,
                                                lastMessage = userLastMessage.ifEmpty { "No messages yet" },
                                                lastMessageTimestamp = userLastMessageTimestamp,
                                                createdAt = document.getTimestamp("createdAt"),
                                                profileImage = profileImage,
                                                contact = contact,
                                                unreadCount = unreadCount
                                            )
                                            Log.d(
                                                "MessagesActivity",
                                                "Added p2p chat: $chatId with $firstName $lastName, Contact: $contact"
                                            )
                                        } else {
                                            Log.w("MessagesActivity", "User document not found for: $otherUserId")
                                            chatMap[chatId] = Chat(
                                                chatId = chatId,
                                                name = "Unknown User",
                                                type = type,
                                                lastMessage = userLastMessage.ifEmpty { "No messages yet" },
                                                lastMessageTimestamp = userLastMessageTimestamp,
                                                createdAt = document.getTimestamp("createdAt"),
                                                profileImage = null,
                                                contact = null,
                                                unreadCount = unreadCount
                                            )
                                            Log.d("MessagesActivity", "Added p2p chat with unknown user: $chatId")
                                        }
                                    } else {
                                        Log.w("MessagesActivity", "No other user found for p2p chat: $chatId")
                                    }
                                }
                                "group" -> {
                                    val name = document.getString("name") ?: "Unnamed Group"
                                    val profileImage = document.getString("groupImage")
                                    chatMap[chatId] = Chat(
                                        chatId = chatId,
                                        name = name,
                                        type = type,
                                        lastMessage = userLastMessage.ifEmpty { "No messages yet" },
                                        lastMessageTimestamp = userLastMessageTimestamp,
                                        createdAt = document.getTimestamp("createdAt"),
                                        profileImage = profileImage,
                                        contact = null,
                                        unreadCount = unreadCount
                                    )
                                    Log.d("MessagesActivity", "Added group chat: $chatId with name: $name")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("MessagesActivity", "Error processing chat document: ${document.id}", e)
                        }
                    }

                    chats.clear()
                    chats.addAll(chatMap.values)
                    Log.d("MessagesActivity", "Total chats loaded: ${chats.size}")
                    filterChats(binding.searchInput.text.toString())
                }
            }
    }

    private fun filterChats(query: String) {
        var filteredChats = if (query.isBlank()) {
            chats
        } else {
            chats.filter { it.name.contains(query, ignoreCase = true) }
        }
        if (unreadFilterEnabled) {
            filteredChats = filteredChats.filter { it.unreadCount > 0 }
        }
        Log.d("MessagesActivity", "Filtered chats count: ${filteredChats.size}")
        adapter.updateChats(filteredChats.sortedByDescending { it.lastMessageTimestamp?.toDate() })
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(messageReceiver)
    }
}
