package com.aorv.blazerider

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.Exclude

data class Message(
    @DocumentId
    val id: String = "",
    val senderId: String = "",
    val content: String = "",
    val timestamp: Timestamp? = null,
    val type: String = "text",
    val status: String = "sent",
    val readBy: List<String> = emptyList(),
    val deletedBy: List<String> = emptyList(),
    // Use @get:Exclude so Firebase doesn't try to save this local UI property
    @get:Exclude
    val showDivider: Boolean = false
)
