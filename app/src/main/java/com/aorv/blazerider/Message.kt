package com.aorv.blazerider

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.PropertyName

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

    @get:Exclude
    var showDivider: Boolean = false // Changed to var so it can be updated in Adapter
) {
    // This empty constructor is explicitly for Firebase deserialization
    constructor() : this("", "", "", null, "text", "sent", emptyList(), emptyList(), false)
}