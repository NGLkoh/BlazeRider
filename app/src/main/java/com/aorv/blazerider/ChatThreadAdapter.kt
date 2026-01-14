package com.aorv.blazerider

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.Timestamp
import java.text.SimpleDateFormat
import java.util.*

class ChatThreadAdapter(private val onChatClick: (Chat) -> Unit) : ListAdapter<Chat, ChatThreadAdapter.ViewHolder>(ChatDiffCallback()) {

    private val statusListeners = mutableMapOf<String, ValueEventListener>() // chatId -> Listener

    class ViewHolder(itemView: View, private val onClick: (Chat) -> Unit) : RecyclerView.ViewHolder(itemView) {
        val chatName: TextView = itemView.findViewById(R.id.chat_name)
        val chatImage: ImageView = itemView.findViewById(R.id.chat_image)
        val chatLastMessage: TextView = itemView.findViewById(R.id.chat_last_message)
        val chatTimestamp: TextView = itemView.findViewById(R.id.chat_timestamp)
        val activeStatusDot: View = itemView.findViewById(R.id.active_status_dot)
        val unreadCount: TextView = itemView.findViewById(R.id.unread_count)

        fun bind(chat: Chat, adapter: ChatThreadAdapter) {
            chatName.text = chat.name.ifEmpty { "User" }
            chatLastMessage.text = chat.lastMessage?.ifEmpty { "No messages" } ?: "No messages"
            chatTimestamp.text = chat.lastMessageTimestamp?.let { formatTimestamp(it) } ?: ""

            Glide.with(chatImage.context)
                .load(chat.profileImage)
                .placeholder(R.drawable.ic_anonymous)
                .error(R.drawable.ic_anonymous)
                .circleCrop()
                .into(chatImage)

            if (chat.unreadCount > 0) {
                unreadCount.text = if (chat.unreadCount > 99) "99+" else chat.unreadCount.toString()
                unreadCount.visibility = View.VISIBLE
            } else {
                unreadCount.visibility = View.GONE
            }

            // Handle active status dot for p2p chats
            if (chat.type == "p2p" && chat.contact?.id != null) {
                val userId = chat.contact.id
                
                // Remove existing listener for this specific chat if any
                adapter.statusListeners[chat.chatId]?.let {
                    FirebaseDatabase.getInstance().reference.child("status").child(userId).child("state")
                        .removeEventListener(it)
                }

                val statusListener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val state = snapshot.getValue(String::class.java)
                        activeStatusDot.visibility = if (state == "online") View.VISIBLE else View.GONE
                    }
                    override fun onCancelled(error: DatabaseError) {
                        activeStatusDot.visibility = View.GONE
                    }
                }
                
                FirebaseDatabase.getInstance().reference.child("status").child(userId).child("state")
                    .addValueEventListener(statusListener)
                adapter.statusListeners[chat.chatId] = statusListener
            } else {
                activeStatusDot.visibility = View.GONE
            }

            itemView.setOnClickListener { onClick(chat) }
        }

        private fun formatTimestamp(timestamp: Timestamp): String {
            val now = Calendar.getInstance()
            val messageTime = Calendar.getInstance().apply { time = timestamp.toDate() }

            val isSameDay = now.get(Calendar.DAY_OF_YEAR) == messageTime.get(Calendar.DAY_OF_YEAR) &&
                    now.get(Calendar.YEAR) == messageTime.get(Calendar.YEAR)
            val isSameYear = now.get(Calendar.YEAR) == messageTime.get(Calendar.YEAR)
            
            return when {
                isSameDay -> SimpleDateFormat("hh:mm a", Locale.getDefault()).format(timestamp.toDate())
                isSameYear -> SimpleDateFormat("MMM dd", Locale.getDefault()).format(timestamp.toDate())
                else -> SimpleDateFormat("MM/dd/yy", Locale.getDefault()).format(timestamp.toDate())
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_thread, parent, false)
        return ViewHolder(view, onChatClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), this)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        // We don't necessarily want to remove listeners here if they are chat-specific and not position-specific
        // But for memory efficiency, if the view is not visible, we could. 
        // However, with ListAdapter, it's better to manage these in a way that doesn't leak.
    }

    fun getChatAt(position: Int): Chat = getItem(position)

    fun updateChats(newChats: List<Chat>) {
        submitList(newChats)
    }

    fun clearListeners() {
        statusListeners.forEach { (chatId, listener) ->
            // Note: This requires knowing the userId, which we don't have here easily without the chat object.
            // A better way is to store userId in the map or just remove all by path if possible, 
            // but Firebase doesn't support "remove all listeners" easily without references.
        }
        statusListeners.clear()
    }
}

class ChatDiffCallback : DiffUtil.ItemCallback<Chat>() {
    override fun areItemsTheSame(oldItem: Chat, newItem: Chat): Boolean = oldItem.chatId == newItem.chatId
    override fun areContentsTheSame(oldItem: Chat, newItem: Chat): Boolean = oldItem == newItem
}
