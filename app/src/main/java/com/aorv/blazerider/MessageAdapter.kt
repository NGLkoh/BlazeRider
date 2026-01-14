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
    private var chatId: String,
    private val db: FirebaseFirestore
) : ListAdapter<Message, MessageViewHolder>(MessageDiffCallback()) {

    private val userCache = mutableMapOf<String, Pair<String, String>>() // uid -> <Name, PhotoUrl>

    fun setChatId(chatId: String) {
        this.chatId = chatId
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view, db, currentUserId, userCache)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        try {
            val message = getItem(position)
            holder.bind(message) { view ->
                showContextMenu(view, message)
            }
        } catch (e: Exception) {
            Log.e("MessageAdapter", "Error binding view holder at position $position", e)
        }
    }

    private fun showContextMenu(view: View, message: Message) {
        val popup = PopupMenu(view.context, view)
        popup.inflate(R.menu.message_context_menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_unsend -> { unsendMessage(message); true }
                R.id.action_delete -> { deleteMessage(message); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun unsendMessage(message: Message) {
        if (chatId.isEmpty()) return
        val messageRef = db.collection("chats").document(chatId).collection("messages").document(message.id)
        messageRef.update(mapOf("content" to "This message was unsent", "type" to "unsent"))
            .addOnSuccessListener { updateLastMessageForUnsend(message) }
    }

    private fun deleteMessage(message: Message) {
        if (chatId.isEmpty()) return
        db.collection("chats").document(chatId).collection("messages").document(message.id)
            .delete().addOnSuccessListener { updateLastMessageAfterDelete(message) }
    }

    private fun updateLastMessageForUnsend(message: Message) {
        if (chatId.isEmpty()) return
        val chatRef = db.collection("chats").document(chatId)
        chatRef.get().addOnSuccessListener { chatDoc ->
            if (!chatDoc.exists()) return@addOnSuccessListener
            val lastMessage = chatDoc.get("lastMessage") as? Map<*, *>
            if (lastMessage?.get("id") == message.id) {
                val batch = db.batch()
                val updatedLastMessage = lastMessage.toMutableMap()
                updatedLastMessage["content"] = "This message was unsent"
                batch.update(chatRef, "lastMessage", updatedLastMessage)
                val members = chatDoc.get("members") as? List<*>
                members?.forEach { userId ->
                    val uid = userId.toString()
                    batch.update(db.collection("userChats").document(uid).collection("chats").document(chatId), "lastMessage", updatedLastMessage)
                }
                batch.commit()
            }
        }
    }

    private fun updateLastMessageAfterDelete(deletedMessage: Message) {
        if (chatId.isEmpty()) return
        val chatRef = db.collection("chats").document(chatId)
        chatRef.get().addOnSuccessListener { chatDoc ->
            if (!chatDoc.exists()) return@addOnSuccessListener
            val lastMessage = chatDoc.get("lastMessage") as? Map<*, *>
            if (lastMessage?.get("id") == deletedMessage.id) {
                db.collection("chats").document(chatId).collection("messages")
                    .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING).limit(1).get()
                    .addOnSuccessListener { messages ->
                        val newLastMessage: Map<String, Any?>? = if (messages.isEmpty) null else {
                            val doc = messages.documents[0]
                            val msg = doc.toObject(Message::class.java)
                            if (msg != null) {
                                val content = when (msg.type) {
                                    "text" -> msg.content
                                    "image" -> "📷 Image"
                                    "file" -> "📎 File"
                                    else -> "Message"
                                }
                                mapOf<String, Any?>(
                                    "id" to doc.id,
                                    "content" to content,
                                    "senderId" to msg.senderId,
                                    "timestamp" to msg.timestamp
                                )
                            } else {
                                null
                            }
                        }
                        val batch = db.batch()
                        batch.update(chatRef, "lastMessage", newLastMessage)
                        val members = chatDoc.get("members") as? List<*>
                        members?.forEach { userId ->
                             val uid = userId.toString()
                             batch.update(db.collection("userChats").document(uid).collection("chats").document(chatId), "lastMessage", newLastMessage)
                        }
                        batch.commit()
                    }
            }
        }
    }
}

class MessageViewHolder(
    itemView: View,
    private val db: FirebaseFirestore,
    private val currentUserId: String,
    private val userCache: MutableMap<String, Pair<String, String>>
) : RecyclerView.ViewHolder(itemView) {
    private val senderName: TextView = itemView.findViewById(R.id.sender_name)
    private val messageText: TextView = itemView.findViewById(R.id.message_text)
    private val timestampText: TextView = itemView.findViewById(R.id.timestamp)
    private val messageContentWrapper: LinearLayout = itemView.findViewById(R.id.message_content_wrapper)
    private val messageRow: LinearLayout = itemView.findViewById(R.id.message_row)
    private val timestampDivider: TextView = itemView.findViewById(R.id.timestamp_divider)
    private val messageImage: ImageView? = itemView.findViewById(R.id.message_image)
    private val profileImage: ShapeableImageView = itemView.findViewById(R.id.profile_image)

    fun bind(message: Message, onLongClick: (View) -> Unit) {
        val isCurrentUser = message.senderId == currentUserId

        messageContentWrapper.setOnClickListener(null)
        messageContentWrapper.setOnLongClickListener(null)

        if (isCurrentUser) {
            messageContentWrapper.setOnLongClickListener { 
                onLongClick(it)
                true
            }
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
                messageContentWrapper.setOnClickListener { timestampText.isVisible = !timestampText.isVisible }
            }
            "file" -> {
                messageText.isVisible = true
                messageImage?.isVisible = false
                messageText.text = "📎 File Attachment"
                messageText.setBackgroundResource(
                    if (isCurrentUser) R.drawable.rounded_message_background_self
                    else R.drawable.rounded_message_background_other
                )
                messageContentWrapper.setOnClickListener {
                    try { 
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(message.content))
                        itemView.context.startActivity(intent) 
                    } catch (e: Exception) {
                        Log.e("MessageAdapter", "Failed to open file", e)
                        Toast.makeText(itemView.context, "Unable to open file", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            "image" -> {
                messageText.isVisible = false
                messageImage?.isVisible = true
                messageImage?.let{
                    Glide.with(itemView.context)
                        .load(message.content)
                        .placeholder(R.drawable.ic_anonymous)
                        .error(R.drawable.ic_anonymous)
                        .into(it)
                }
                messageContentWrapper.setOnClickListener {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(message.content))
                        intent.setDataAndType(Uri.parse(message.content), "image/*")
                        itemView.context.startActivity(intent)
                    } catch (e: Exception) {
                        Log.e("MessageAdapter", "Failed to view photo", e)
                        Toast.makeText(itemView.context, "Unable to view photo", Toast.LENGTH_SHORT).show()
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

        messageText.setTextColor(ContextCompat.getColor(itemView.context,
            if (isCurrentUser) android.R.color.white else android.R.color.black))

        if (message.type == "unsent") {
            messageText.setTextColor(ContextCompat.getColor(itemView.context, R.color.gray))
        }

        val dateFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        timestampText.text = message.timestamp?.let { dateFormat.format(it.toDate()) } ?: ""
        timestampText.isVisible = false

        timestampDivider.isVisible = message.showDivider && message.timestamp != null
        if (message.showDivider && message.timestamp != null) {
            timestampDivider.text = formatDividerTimestamp(message.timestamp)
        }

        // Layout alignment
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

        profileImage.isVisible = !isCurrentUser
        if (!isCurrentUser) {
            val cachedUser = userCache[message.senderId]
            if (cachedUser != null) {
                senderName.text = cachedUser.first
                Glide.with(itemView.context).load(cachedUser.second.ifEmpty { R.drawable.ic_anonymous })
                    .circleCrop().into(profileImage)
            } else {
                senderName.text = "..."
                profileImage.setImageResource(R.drawable.ic_anonymous)
                db.collection("users").document(message.senderId).get()
                    .addOnSuccessListener { document ->
                        if (document.exists()) {
                            val name = "${document.getString("firstName") ?: ""} ${document.getString("lastName") ?: ""}".trim()
                            val photoUrl = document.getString("profileImageUrl") ?: ""
                            userCache[message.senderId] = Pair(name, photoUrl)
                            if (bindingAdapterPosition != RecyclerView.NO_POSITION) {
                                senderName.text = name
                                Glide.with(itemView.context).load(photoUrl.ifEmpty { R.drawable.ic_anonymous })
                                    .circleCrop().into(profileImage)
                            }
                        }
                    }
            }
        }
    }

    private fun formatDividerTimestamp(timestamp: Timestamp): String {
        val now = Calendar.getInstance()
        val timeDiff = now.timeInMillis - timestamp.toDate().time
        val oneDayMillis = 24 * 60 * 60 * 1000L
        return when {
            timeDiff < oneDayMillis -> "--- ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(timestamp.toDate())} ---"
            timeDiff < 2 * oneDayMillis -> "--- Yesterday at ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(timestamp.toDate())} ---"
            else -> "--- ${SimpleDateFormat("MMM d 'at' h:mm a", Locale.getDefault()).format(timestamp.toDate())} ---"
        }
    }

    private fun Int.dpToPx(context: Context): Int = (this * context.resources.displayMetrics.density).toInt()
}

class MessageDiffCallback : DiffUtil.ItemCallback<Message>() {
    override fun areItemsTheSame(oldItem: Message, newItem: Message): Boolean = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: Message, newItem: Message): Boolean = oldItem == newItem
}
