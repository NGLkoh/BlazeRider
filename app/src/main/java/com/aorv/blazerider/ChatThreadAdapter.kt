package com.aorv.blazerider

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.Timestamp
import java.text.SimpleDateFormat
import java.util.*

class ChatThreadAdapter(private val onChatClick: (Chat) -> Unit) : RecyclerView.Adapter<ChatThreadAdapter.ViewHolder>() {
    private val chats = mutableListOf<Chat>()
    private val statusListeners = mutableMapOf<Int, ValueEventListener>() // Track listeners to remove them

    class ViewHolder(itemView: View, private val onClick: (Chat) -> Unit) : RecyclerView.ViewHolder(itemView) {
        val chatName: TextView = itemView.findViewById(R.id.chat_name)
        val chatImage: ImageView = itemView.findViewById(R.id.chat_image)
        val chatLastMessage: TextView = itemView.findViewById(R.id.chat_last_message)
        val chatTimestamp: TextView = itemView.findViewById(R.id.chat_timestamp)
        val activeStatusDot: View = itemView.findViewById(R.id.active_status_dot)
        val unreadCount: TextView = itemView.findViewById(R.id.unread_count)

        fun bind(chat: Chat, adapter: ChatThreadAdapter) {
            // Bind chat name
            chatName.text = chat.name.ifEmpty { "Unknown User" }

            // Bind last message
            chatLastMessage.text = chat.lastMessage ?: "No messages yet"

            // Bind timestamp
            chatTimestamp.text = chat.lastMessageTimestamp?.let { formatTimestamp(it) } ?: ""

            // Bind profile image
            Glide.with(chatImage.context)
                .load(chat.profileImage)
                .placeholder(R.drawable.ic_anonymous)
                .error(R.drawable.ic_anonymous)
                .into(chatImage)

            // Bind unread count
            if (chat.unreadCount > 0) {
                unreadCount.text = chat.unreadCount.toString()
                unreadCount.visibility = View.VISIBLE
            } else {
                unreadCount.visibility = View.GONE
            }

            // Handle active status dot for p2p chats
            if (chat.type == "p2p" && chat.contact?.id != null) {
                val userId = chat.contact.id
                // Remove any existing listener for this position
                adapter.statusListeners[adapterPosition]?.let { listener ->
                    FirebaseDatabase.getInstance().reference
                        .child("status").child(userId).child("state")
                        .removeEventListener(listener)
                    adapter.statusListeners.remove(adapterPosition)
                }

                // Add new listener for user status
                val statusListener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val state = snapshot.getValue(String::class.java)
                        activeStatusDot.visibility = if (state == "online") {
                            View.VISIBLE
                        } else {
                            View.GONE
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Log.e("ChatThreadAdapter", "Failed to read user status for $userId: ${error.message}")
                        activeStatusDot.visibility = View.GONE
                    }
                }
                FirebaseDatabase.getInstance().reference
                    .child("status").child(userId).child("state")
                    .addValueEventListener(statusListener)
                adapter.statusListeners[adapterPosition] = statusListener
            } else {
                // Hide dot for group chats or if no contact ID
                activeStatusDot.visibility = View.GONE
            }

            // Set click listener
            itemView.setOnClickListener { onClick(chat) }
        }

        private fun formatTimestamp(timestamp: Timestamp): String {
            val now = Calendar.getInstance()
            val messageTime = Calendar.getInstance().apply { time = timestamp.toDate() }

            val isSameDay = now.get(Calendar.DAY_OF_YEAR) == messageTime.get(Calendar.DAY_OF_YEAR) &&
                    now.get(Calendar.YEAR) == messageTime.get(Calendar.YEAR)
            val isSameYear = now.get(Calendar.YEAR) == messageTime.get(Calendar.YEAR)
            val daysDiff = (now.timeInMillis - messageTime.timeInMillis) / (1000 * 60 * 60 * 24)

            return when {
                isSameDay -> SimpleDateFormat("hh:mm a", Locale.getDefault()).format(timestamp.toDate())
                daysDiff <= 7 -> SimpleDateFormat("EEE", Locale.getDefault()).format(timestamp.toDate())
                isSameYear -> SimpleDateFormat("MMM dd", Locale.getDefault()).format(timestamp.toDate())
                else -> SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(timestamp.toDate())
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_thread, parent, false)
        return ViewHolder(view, onChatClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(chats[position], this)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        // Remove listener when ViewHolder is recycled to prevent memory leaks
        statusListeners[holder.adapterPosition]?.let { listener ->
            if (holder.adapterPosition != RecyclerView.NO_POSITION) {
                val chat = chats.getOrNull(holder.adapterPosition)
                if (chat?.type == "p2p" && chat.contact?.id != null) {
                    FirebaseDatabase.getInstance().reference
                        .child("status").child(chat.contact.id).child("state")
                        .removeEventListener(listener)
                }
            }
            statusListeners.remove(holder.adapterPosition)
        }
        holder.activeStatusDot.visibility = View.GONE
    }

    override fun getItemCount(): Int = chats.size

    fun getChatAt(position: Int): Chat {
        return chats[position]
    }

    fun updateChats(newChats: List<Chat>) {
        // Remove all existing listeners before updating
        statusListeners.forEach { (position, listener) ->
            val chat = chats.getOrNull(position)
            if (chat?.type == "p2p" && chat.contact?.id != null) {
                FirebaseDatabase.getInstance().reference
                    .child("status").child(chat.contact.id).child("state")
                    .removeEventListener(listener)
            }
        }
        statusListeners.clear()
        chats.clear()
        chats.addAll(newChats)
        notifyDataSetChanged()
    }
}
