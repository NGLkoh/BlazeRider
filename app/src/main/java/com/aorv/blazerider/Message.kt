package com.aorv.blazerider

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId

data class Message(
    @DocumentId
    val id: String = "",
    val senderId: String = "",
    val content: String = "",
    val timestamp: Timestamp? = null,
    val type: String = "text", // "text", "file", "image", "unsent"
    val status: String = "sent", // "sent", "delivered", "read"
    val readBy: List<String> = emptyList(),
    val showDivider: Boolean = false
)
