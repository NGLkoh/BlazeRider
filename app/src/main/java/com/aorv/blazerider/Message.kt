package com.aorv.blazerider

import com.google.firebase.Timestamp

data class Message(
    val id: String = "",
    val senderId: String = "",
    val content: String = "",
    val timestamp: Timestamp = Timestamp.now(),
    val type: String = "text", // "text", "file", "image"
    val status: String = "sent", // "sent", "delivered", "read"
    val readBy: List<String> = emptyList(),
    val showDivider: Boolean = false
)
