package com.aorv.blazerider

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
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.imageview.ShapeableImageView
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.*

class MessageAdapter(
    private val currentUserId: String,
    private var chatId: String,
    private val db: FirebaseFirestore
) : ListAdapter<Message, MessageViewHolder>(MessageDiffCallback()) {

    private val userCache = mutableMapOf<String, Pair<String, String>>()

    fun setChatId(chatId: String) {
        this.chatId = chatId
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view, db, currentUserId, userCache)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        try {
            val message = getItem(position)
            // Long click handler passed to the ViewHolder
            holder.bind(message) { view -> showContextMenu(view, message) }
        } catch (e: Exception) {
            Log.e("MessageAdapter", "Error binding at $position", e)
        }
    }

    private fun showContextMenu(view: View, message: Message) {
        val popup = PopupMenu(view.context, view)
        popup.inflate(R.menu.message_context_menu)
        val unsendItem = popup.menu.findItem(R.id.action_unsend)

        // Logic: You can unsend if you are the sender and it's not already unsent.
        // This now works for 'text', 'image', and 'file' types.
        unsendItem.isVisible = (message.senderId == currentUserId && message.type != "unsent")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_unsend -> { unsendMessage(message); true }
                R.id.action_delete -> { deleteMessageForMe(message); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun unsendMessage(message: Message) {
        if (chatId.isEmpty() || message.id.isEmpty()) return
        val messageRef = db.collection("chats").document(chatId).collection("messages").document(message.id)

        // When unsending an image, we change the type to "unsent".
        // The displayUnsent() function in ViewHolder will then hide the image and show the text.
        messageRef.update(mapOf(
            "content" to "This message was unsent",
            "type" to "unsent"
        ))
    }

    private fun deleteMessageForMe(message: Message) {
        if (chatId.isEmpty() || message.id.isEmpty()) return
        db.collection("chats").document(chatId).collection("messages").document(message.id)
            .update("deletedBy", FieldValue.arrayUnion(currentUserId))
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
    private val statusIndicator: TextView? = itemView.findViewById(R.id.status_indicator)

    fun bind(message: Message, onLongClick: (View) -> Unit) {
        val isSelf = message.senderId == currentUserId

        // Layout Alignment
        messageRow.gravity = if (isSelf) Gravity.END else Gravity.START
        (messageContentWrapper.layoutParams as LinearLayout.LayoutParams).gravity = if (isSelf) Gravity.END else Gravity.START
        (timestampText.layoutParams as LinearLayout.LayoutParams).gravity = if (isSelf) Gravity.END else Gravity.START

        // Visibility Reset
        profileImage.isVisible = !isSelf
        senderName.isVisible = false
        messageText.isVisible = false
        messageImage?.isVisible = false
        statusIndicator?.isVisible = isSelf

        // Long click listener on the wrapper ensures images can be unsent too
        messageContentWrapper.setOnLongClickListener {
            onLongClick(it)
            true
        }

        if (message.type == "unsent") {
            displayUnsent(isSelf)
        } else {
            when (message.type) {
                "text" -> displayText(message, isSelf)
                "file" -> displayFile(message, isSelf)
                "image" -> displayImage(message, isSelf)
            }
            if (isSelf) updateStatusIndicator(message.status)
        }

        setupMetadata(isSelf, message)
    }

    private fun displayUnsent(isSelf: Boolean) {
        messageText.isVisible = true
        messageImage?.isVisible = false // Ensure image is hidden when unsent
        messageText.text = "This message was unsent"
        messageText.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.darker_gray))
        messageText.setBackgroundResource(
            if (isSelf) R.drawable.rounded_message_background_self
            else R.drawable.rounded_message_background_other
        )
    }

    private fun displayText(message: Message, isSelf: Boolean) {
        messageText.isVisible = true
        messageText.text = message.content
        messageText.setTextColor(ContextCompat.getColor(itemView.context, if (isSelf) android.R.color.white else android.R.color.black))
        messageText.setBackgroundResource(
            if (isSelf) R.drawable.rounded_message_background_self
            else R.drawable.rounded_message_background_other
        )
        messageContentWrapper.setOnClickListener { timestampText.isVisible = !timestampText.isVisible }
    }

    private fun displayFile(message: Message, isSelf: Boolean) {
        messageText.isVisible = true
        messageText.text = "📎 File Attachment"
        messageText.setBackgroundResource(
            if (isSelf) R.drawable.rounded_message_background_self
            else R.drawable.rounded_message_background_other
        )
        messageContentWrapper.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(message.content))
            itemView.context.startActivity(intent)
        }
    }

    private fun displayImage(message: Message, isSelf: Boolean) {
        messageImage?.isVisible = true
        messageImage?.let { imageView ->
            Glide.with(itemView.context)
                .load(message.content)
                .into(imageView)

            imageView.setOnClickListener {
                val intent = Intent(itemView.context, FullScreenImageActivity::class.java).apply {
                    putExtra("IMAGE_URL", message.content)
                }
                itemView.context.startActivity(intent)
            }
        }
    }

    private fun updateStatusIndicator(status: String) {
        statusIndicator?.let {
            it.text = if (status == "read") "Read" else "Sent"
            it.setTextColor(ContextCompat.getColor(itemView.context,
                if (status == "read") android.R.color.holo_blue_dark else android.R.color.darker_gray))
        }
    }

    private fun setupMetadata(isSelf: Boolean, message: Message) {
        if (!isSelf) fetchUserInfo(message.senderId)
        val dateFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        timestampText.text = message.timestamp?.let { dateFormat.format(it.toDate()) } ?: ""
        timestampDivider.isVisible = message.showDivider && message.timestamp != null
        if (timestampDivider.isVisible) {
            timestampDivider.text = "--- ${SimpleDateFormat("MMM d", Locale.getDefault()).format(message.timestamp!!.toDate())} ---"
        }
    }

    private fun fetchUserInfo(uid: String) {
        val cached = userCache[uid]
        if (cached != null) {
            Glide.with(itemView.context).load(cached.second.ifEmpty { R.drawable.ic_anonymous }).circleCrop().into(profileImage)
        } else {
            db.collection("users").document(uid).get().addOnSuccessListener { doc ->
                if (doc.exists()) {
                    val url = doc.getString("profileImageUrl") ?: ""
                    userCache[uid] = Pair("", url)
                    Glide.with(itemView.context).load(url.ifEmpty { R.drawable.ic_anonymous }).circleCrop().into(profileImage)
                }
            }
        }
    }
}

class MessageDiffCallback : DiffUtil.ItemCallback<Message>() {
    override fun areItemsTheSame(old: Message, new: Message) = old.id == new.id
    override fun areContentsTheSame(old: Message, new: Message) = old == new
}