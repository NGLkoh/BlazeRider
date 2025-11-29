package com.aorv.blazerider

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.imageview.ShapeableImageView
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.*

class MessageAdapter(
    private val currentUserId: String,
    private val chatId: String,
    private val db: FirebaseFirestore
) : ListAdapter<Message, MessageViewHolder>(MessageDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view, db, chatId, currentUserId)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = getItem(position)
        holder.bind(message)
    }
}

class MessageViewHolder(
    itemView: View,
    private val db: FirebaseFirestore,
    private val chatId: String,
    private val currentUserId: String
) : RecyclerView.ViewHolder(itemView) {
    private val senderName: TextView = itemView.findViewById(R.id.sender_name)
    private val messageText: TextView = itemView.findViewById(R.id.message_text)
    private val timestampText: TextView = itemView.findViewById(R.id.timestamp)
    private val messageContentWrapper: LinearLayout = itemView.findViewById(R.id.message_content_wrapper)
    private val messageRow: LinearLayout = itemView.findViewById(R.id.message_row)
    private val timestampDivider: TextView = itemView.findViewById(R.id.timestamp_divider)
    private val messageImage: ImageView? = itemView.findViewById(R.id.message_image)
    private val profileImage: ShapeableImageView = itemView.findViewById(R.id.profile_image)

    fun bind(message: Message) {
        val isCurrentUser = message.senderId == currentUserId

        // Reset listeners to avoid conflicts from view recycling
        messageContentWrapper.setOnClickListener(null)
        messageContentWrapper.setOnLongClickListener(null)

        if (isCurrentUser) {
            messageContentWrapper.setOnLongClickListener { 
                showContextMenu(it, message)
                true
            }
        } else {
            messageContentWrapper.isLongClickable = false
        }

        when (message.type) {
            "text" -> {
                messageText.isVisible = true
                messageImage?.isVisible = false
                messageText.text = message.content
                messageText.setBackgroundResource(
                    if (isCurrentUser) R.drawable.rounded_message_background_self
                    else R.drawable.rounded_message_background_other
                )
                messageContentWrapper.setOnClickListener {
                    timestampText.isVisible = !timestampText.isVisible
                }
            }
            "file" -> {
                messageText.isVisible = true
                messageImage?.isVisible = false
                messageText.text = "📎 File"
                messageText.setBackgroundResource(
                    if (isCurrentUser) R.drawable.rounded_message_background_self
                    else R.drawable.rounded_message_background_other
                )
                messageContentWrapper.setOnClickListener {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(message.content))
                    try {
                        itemView.context.startActivity(intent)
                    } catch (e: Exception) {
                        Log.e("MessageViewHolder", "Failed to open file", e)
                        Toast.makeText(itemView.context, "Unable to open file", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            "image" -> {
                messageText.isVisible = false
                messageImage?.isVisible = true
                Glide.with(itemView.context)
                    .load(message.content)
                    .placeholder(R.drawable.ic_anonymous)
                    .error(R.drawable.ic_anonymous)
                    .into(messageImage!!)
                messageContentWrapper.setOnClickListener {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(message.content))
                    intent.setDataAndType(Uri.parse(message.content), "image/*")
                    try {
                        itemView.context.startActivity(intent)
                    } catch (e: Exception) {
                        Log.e("MessageViewHolder", "Failed to open image", e)
                        Toast.makeText(itemView.context, "Unable to open image", Toast.LENGTH_SHORT).show()
                    }
                }
            }
             "unsent" -> {
                messageText.isVisible = true
                messageImage?.isVisible = false
                messageText.text = "This message was unsent"
                messageText.setBackgroundResource(
                    if (isCurrentUser) R.drawable.rounded_message_background_self
                    else R.drawable.rounded_message_background_other
                )
            }
        }

        messageText.setTextColor(
            ContextCompat.getColor(
                itemView.context,
                if (isCurrentUser) android.R.color.white else android.R.color.black
            )
        )

        if (message.type == "unsent") {
             messageText.setTextColor(ContextCompat.getColor(itemView.context, R.color.gray))
        }

        val dateFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        timestampText.text = dateFormat.format(message.timestamp.toDate())
        timestampText.isVisible = false

        timestampDivider.isVisible = message.showDivider
        if (message.showDivider) {
            timestampDivider.text = formatDividerTimestamp(message.timestamp)
        }

        val rowParams = messageRow.layoutParams as LinearLayout.LayoutParams
        rowParams.gravity = if (isCurrentUser) Gravity.END else Gravity.START
        rowParams.marginStart = if (isCurrentUser) 48.dpToPx(itemView.context) else 0
        rowParams.marginEnd = if (isCurrentUser) 8.dpToPx(itemView.context) else 48.dpToPx(itemView.context)
        messageRow.layoutParams = rowParams

        val contentParams = messageContentWrapper.layoutParams as LinearLayout.LayoutParams
        contentParams.gravity = if (isCurrentUser) Gravity.END else Gravity.START
        contentParams.marginStart = if (isCurrentUser) 48.dpToPx(itemView.context) else 8.dpToPx(itemView.context)
        contentParams.marginEnd = if (isCurrentUser) 8.dpToPx(itemView.context) else 48.dpToPx(itemView.context)
        messageContentWrapper.layoutParams = contentParams

        messageContentWrapper.gravity = if (isCurrentUser) Gravity.END else Gravity.START

        val timestampParams = timestampText.layoutParams as LinearLayout.LayoutParams
        timestampParams.gravity = if (isCurrentUser) Gravity.END else Gravity.START
        timestampParams.marginStart = if (isCurrentUser) 48.dpToPx(itemView.context) else 12.dpToPx(itemView.context)
        timestampParams.marginEnd = if (isCurrentUser) 12.dpToPx(itemView.context) else 48.dpToPx(itemView.context)
        timestampText.layoutParams = timestampParams

        profileImage.isVisible = !isCurrentUser
        if (!isCurrentUser) {
            db.collection("users").document(message.senderId).get()
                .addOnSuccessListener { document ->
                    val photoUrl = document.getString("profileImageUrl") ?: ""
                    val name = document.getString("name") ?: "Unknown"
                    senderName.text = name

                    if (photoUrl.isNotEmpty()) {
                        Glide.with(itemView.context)
                            .load(photoUrl)
                            .placeholder(R.drawable.ic_anonymous)
                            .error(R.drawable.ic_anonymous)
                            .circleCrop()
                            .into(profileImage)
                    } else {
                        Glide.with(itemView.context)
                            .load(R.drawable.ic_anonymous)
                            .circleCrop()
                            .into(profileImage)
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("MessageViewHolder", "Failed to fetch sender data", e)
                    Glide.with(itemView.context)
                        .load(R.drawable.ic_anonymous)
                        .circleCrop()
                        .into(profileImage)
                }
        }

        if (!isCurrentUser && !message.readBy.contains(currentUserId)) {
            val updatedReadBy = message.readBy + currentUserId
            db.collection("chats").document(chatId)
                .collection("messages").document(message.id)
                .update("readBy", updatedReadBy)
                .addOnSuccessListener {
                    db.collection("userChats").document(currentUserId)
                        .collection("chats").document(chatId)
                        .update("unreadCount", 0)
                }
                .addOnFailureListener { e ->
                    Log.e("MessageViewHolder", "Failed to update readBy", e)
                }
        }
    }
    
    private fun showContextMenu(view: View, message: Message) {
        val popup = PopupMenu(view.context, view)
        popup.inflate(R.menu.message_context_menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_unsend -> {
                    unsendMessage(message)
                    true
                }
                R.id.action_delete -> {
                    deleteMessage(message)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun unsendMessage(message: Message) {
        val messageRef = db.collection("chats").document(chatId).collection("messages").document(message.id)
        val update = mapOf(
            "content" to "This message was unsent",
            "type" to "unsent"
        )
        messageRef.update(update)
            .addOnSuccessListener {
                updateLastMessageForUnsend(message)
            }
            .addOnFailureListener {
                Toast.makeText(itemView.context, "Failed to unsend message", Toast.LENGTH_SHORT).show()
            }
    }

    private fun deleteMessage(message: Message) {
        db.collection("chats").document(chatId).collection("messages").document(message.id)
            .delete()
            .addOnSuccessListener {
                updateLastMessageAfterDelete(message)
            }
            .addOnFailureListener {
                Toast.makeText(itemView.context, "Failed to delete message", Toast.LENGTH_SHORT).show()
            }
    }

    private fun updateLastMessageForUnsend(message: Message) {
        val chatRef = db.collection("chats").document(chatId)
        chatRef.get().addOnSuccessListener { chatDoc ->
            val lastMessage = chatDoc.get("lastMessage") as? Map<String, Any>
            val lastMessageId = lastMessage?.get("id") as? String
            if (lastMessage != null && lastMessageId == message.id) {
                val batch = db.batch()
                val updatedLastMessage = lastMessage.toMutableMap()
                updatedLastMessage["content"] = "This message was unsent"

                batch.update(chatRef, "lastMessage", updatedLastMessage)

                val members = chatDoc.get("members") as? Map<String, Any>
                members?.keys?.forEach { userId ->
                    val userChatRef = db.collection("userChats").document(userId).collection("chats").document(chatId)
                    batch.update(userChatRef, "lastMessage", updatedLastMessage)
                }
                batch.commit()
            }
        }
    }

    private fun updateLastMessageAfterDelete(deletedMessage: Message) {
        val chatRef = db.collection("chats").document(chatId)
        chatRef.get().addOnSuccessListener { chatDoc ->
            val lastMessage = chatDoc.get("lastMessage") as? Map<String, Any>
            val lastMessageId = lastMessage?.get("id") as? String
            if (lastMessage != null && lastMessageId == deletedMessage.id) {
                db.collection("chats").document(chatId)
                    .collection("messages")
                    .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                    .limit(1)
                    .get()
                    .addOnSuccessListener { messages ->
                        val newLastMessage: Map<String, Any>? = if (messages.isEmpty) {
                            null
                        } else {
                            val doc = messages.documents[0]
                            val message = doc.toObject(Message::class.java)!!.copy(id = doc.id)
                            val content = when(message.type) {
                                "text" -> message.content
                                "unsent" -> "This message was unsent"
                                else -> "Attachment"
                            }
                            mapOf(
                                "id" to message.id,
                                "content" to content,
                                "senderId" to message.senderId,
                                "timestamp" to message.timestamp
                            )
                        }

                        val batch = db.batch()
                        batch.update(chatRef, "lastMessage", newLastMessage)

                        val members = chatDoc.get("members") as? Map<String, Any>
                        members?.keys?.forEach { userId ->
                             val userChatRef = db.collection("userChats").document(userId).collection("chats").document(chatId)
                             batch.update(userChatRef, "lastMessage", newLastMessage)
                        }
                        batch.commit()
                    }
            }
        }
    }

    private fun formatDividerTimestamp(timestamp: Timestamp): String {
        val now = Calendar.getInstance()
        val messageTime = Calendar.getInstance().apply { time = timestamp.toDate() }
        val timeDiff = now.timeInMillis - timestamp.toDate().time
        val oneDayMillis = 24 * 60 * 60 * 1000L
        val oneWeekMillis = 7 * oneDayMillis

        return when {
            timeDiff < oneDayMillis -> {
                val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
                "--- ${timeFormat.format(timestamp.toDate())} ---"
            }
            timeDiff < 2 * oneDayMillis && isYesterday(now, messageTime) -> {
                val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
                "--- Yesterday at ${timeFormat.format(timestamp.toDate())} ---"
            }
            timeDiff < oneWeekMillis -> {
                val dayFormat = SimpleDateFormat("EEE 'at' h:mm a", Locale.getDefault())
                "--- ${dayFormat.format(timestamp.toDate())} ---"
            }
            else -> {
                val dateFormat = SimpleDateFormat("MMM d 'at' h:mm a", Locale.getDefault())
                "--- ${dateFormat.format(timestamp.toDate())} ---"
            }
        }
    }

    private fun isYesterday(now: Calendar, messageTime: Calendar): Boolean {
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        return messageTime.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR) &&
                messageTime.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR)
    }

    private fun Int.dpToPx(context: Context): Int {
        return (this * context.resources.displayMetrics.density).toInt()
    }
}

class MessageDiffCallback : DiffUtil.ItemCallback<Message>() {
    override fun areItemsTheSame(oldItem: Message, newItem: Message): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: Message, newItem: Message): Boolean {
        return oldItem == newItem
    }
}
