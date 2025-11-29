package com.aorv.blazerider
import com.google.firebase.Timestamp
import com.google.firebase.database.PropertyName

data class Notification(
    val actorId: String? = null,
    val createdAt: Timestamp? = null, // Changed from Long to Timestamp
    val entityId: String? = null,
    val entityType: String = "",
    val isRead: Boolean = false,
    val message: String = "",
    val metadata: Map<String, Any> = emptyMap(),
    val type: String = "",
    val updatedAt: Timestamp? = null, // Changed from Long to Timestamp
    val documentId: String = ""
)
