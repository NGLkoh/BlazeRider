package com.aorv.blazerider

import android.content.Intent
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aorv.blazerider.databinding.ActivityMessagesBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.*

class MessagesActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMessagesBinding
    private lateinit var adapter: ChatThreadAdapter
    private val db = FirebaseFirestore.getInstance()
    private val chats = mutableListOf<Chat>()
    private val auth = FirebaseAuth.getInstance()
    private var chatListener: ListenerRegistration? = null
    private var bannerListener: ListenerRegistration? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var updateJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMessagesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val userId = auth.currentUser?.uid
        if (userId == null) {
            Toast.makeText(this, "Please log in to view messages", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnCompose.setOnClickListener {
            startActivity(Intent(this, NewChatActivity::class.java))
        }

        adapter = ChatThreadAdapter { chat ->
            val intent = Intent(this, ChatConversationActivity::class.java)
            intent.putExtra("chatId", chat.chatId)
            if (chat.type == "p2p" && chat.contact != null) {
                intent.putExtra("CONTACT", chat.contact)
            }
            startActivity(intent)
        }
        binding.messagesRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.messagesRecyclerView.adapter = adapter

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { filterChats(s.toString()) }
        })

        binding.filterChipGroup.setOnCheckedChangeListener { _, _ ->
            filterChats(binding.searchInput.text.toString())
        }

        setupSwipeToDelete()
        listenForNotifications(userId)
    }

    private fun setupSwipeToDelete() {
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val chat = adapter.getChatAt(position)
                    showDeleteConfirmationDialog(chat)
                }
            }
        })
        itemTouchHelper.attachToRecyclerView(binding.messagesRecyclerView)
    }

    override fun onResume() {
        super.onResume()
        startChatListener()
    }

    override fun onPause() {
        super.onPause()
        chatListener?.remove()
        updateJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        bannerListener?.remove()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun startChatListener() {
        val userId = auth.currentUser?.uid ?: return
        
        chatListener = db.collection("userChats").document(userId).collection("chats")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("MessagesActivity", "Listen failed.", error)
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener

                updateJob?.cancel()
                updateJob = lifecycleScope.launch {
                    try {
                        val updatedChats = mutableListOf<Chat>()
                        
                        for (doc in snapshot.documents) {
                            val chatId = doc.id
                            val unreadCount = doc.getLong("unreadCount")?.toInt() ?: 0
                            val lastMessageMap = doc.get("lastMessage") as? Map<*, *>
                            
                            val chatDoc = db.collection("chats").document(chatId).get().await()
                            if (!chatDoc.exists()) continue

                            val type = chatDoc.getString("type") ?: "group"
                            val createdAt = chatDoc.getTimestamp("createdAt")
                            val lastMessage = lastMessageMap?.get("content") as? String ?: ""
                            val timestamp = lastMessageMap?.get("timestamp") as? Timestamp ?: createdAt ?: Timestamp.now()

                            var chatName = ""
                            var profileImage = ""
                            var contact: Contact? = null

                            if (type == "p2p") {
                                val members = chatDoc.get("members") as? List<String> ?: emptyList()
                                val otherUserId = members.firstOrNull { it != userId }
                                if (otherUserId != null) {
                                    val userDoc = db.collection("users").document(otherUserId).get().await()
                                    if (userDoc.exists()) {
                                        val firstName = userDoc.getString("firstName") ?: ""
                                        val lastName = userDoc.getString("lastName") ?: ""
                                        chatName = "$firstName $lastName".trim().ifEmpty { "User" }
                                        profileImage = userDoc.getString("profileImageUrl") ?: ""
                                        contact = Contact(otherUserId, firstName, lastName, profileImage)
                                    }
                                }
                            } else {
                                chatName = chatDoc.getString("name") ?: "Group Chat"
                                profileImage = chatDoc.getString("groupImage") ?: ""
                            }

                            updatedChats.add(Chat(
                                chatId = chatId,
                                name = chatName,
                                type = type,
                                lastMessage = lastMessage,
                                lastMessageTimestamp = timestamp,
                                createdAt = createdAt,
                                profileImage = profileImage,
                                contact = contact,
                                unreadCount = unreadCount
                            ))
                        }

                        chats.clear()
                        chats.addAll(updatedChats.sortedByDescending { it.lastMessageTimestamp })
                        if (!isFinishing && !isDestroyed) {
                            filterChats(binding.searchInput.text.toString())
                        }
                    } catch (e: Exception) {
                        Log.e("MessagesActivity", "Error updating chats", e)
                    }
                }
            }
    }

    private fun listenForNotifications(userId: String) {
        bannerListener = db.collection("userChats").document(userId).collection("chats")
            .addSnapshotListener { snapshot, e ->
                if (e != null || snapshot == null) return@addSnapshotListener
                for (dc in snapshot.documentChanges) {
                    if (dc.type == DocumentChange.Type.MODIFIED) {
                        val unreadCount = dc.document.getLong("unreadCount") ?: 0
                        val lastMessageMap = dc.document.get("lastMessage") as? Map<*, *>
                        val senderId = lastMessageMap?.get("senderId") as? String
                        
                        if (unreadCount > 0 && senderId != null && senderId != userId) {
                            db.collection("users").document(senderId).get().addOnSuccessListener { userDoc ->
                                if (userDoc.exists()) {
                                    val name = "${userDoc.getString("firstName") ?: ""} ${userDoc.getString("lastName") ?: ""}".trim()
                                    showNotificationBanner(name, lastMessageMap["content"] as? String ?: "New message")
                                }
                            }
                        }
                    }
                }
            }
    }

    private fun playNotificationSound() {
        try {
            val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val r = RingtoneManager.getRingtone(applicationContext, notification)
            r?.play()
        } catch (e: Exception) {
            Log.e("MessagesActivity", "Error playing sound", e)
        }
    }

    private fun showNotificationBanner(senderName: String, message: String) {
        mainHandler.post {
            if (isFinishing || isDestroyed) return@post
            playNotificationSound()
            binding.notificationBanner.notificationTitle.text = "New Message"
            binding.notificationBanner.notificationMessage.text = "$senderName: $message"
            binding.notificationBanner.root.visibility = View.VISIBLE
            
            binding.notificationBanner.root.setOnClickListener {
                binding.notificationBanner.root.visibility = View.GONE
            }

            binding.notificationBanner.notificationDismiss.setOnClickListener {
                binding.notificationBanner.root.visibility = View.GONE
            }

            mainHandler.removeCallbacksAndMessages("banner_timeout")
            mainHandler.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    binding.notificationBanner.root.visibility = View.GONE
                }
            }, "banner_timeout", 5000)
        }
    }

    private fun filterChats(query: String) {
        var filteredList = chats.filter { it.name.contains(query, ignoreCase = true) }
        when (binding.filterChipGroup.checkedChipId) {
            R.id.chip_unread -> filteredList = filteredList.filter { it.unreadCount > 0 }
            R.id.chip_groups -> filteredList = filteredList.filter { it.type == "group" }
        }
        adapter.updateChats(filteredList)
    }

    private fun showDeleteConfirmationDialog(chat: Chat) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Chat")
            .setMessage("Are you sure you want to delete this chat history?")
            .setNegativeButton("Cancel") { dialog, _ ->
                adapter.notifyDataSetChanged()
                dialog.dismiss()
            }
            .setPositiveButton("Delete") { _, _ -> deleteChat(chat) }
            .setOnCancelListener { adapter.notifyDataSetChanged() }
            .show()
    }

    private fun deleteChat(chat: Chat) {
        val userId = auth.currentUser?.uid ?: return
        lifecycleScope.launch {
            try {
                db.collection("userChats").document(userId).collection("chats").document(chat.chatId).delete().await()
                chats.removeAll { it.chatId == chat.chatId }
                filterChats(binding.searchInput.text.toString())
            } catch (e: Exception) {
                Log.e("MessagesActivity", "Error deleting chat", e)
                Toast.makeText(this@MessagesActivity, "Failed to delete chat", Toast.LENGTH_SHORT).show()
                adapter.notifyDataSetChanged()
            }
        }
    }
}
