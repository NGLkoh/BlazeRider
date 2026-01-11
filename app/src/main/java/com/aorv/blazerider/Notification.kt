package com.aorv.blazerider
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName

data class Notification(
    @DocumentId 
    val documentId: String = "",
    
    val actorId: String? = null,
    val createdAt: Timestamp? = null,
    val entityId: String? = null,
    val entityType: String = "",
    
    @get:PropertyName("isRead")
    @set:PropertyName("isRead")
    var isRead: Boolean = false,
    
    val message: String = "",
    val metadata: Map<String, Any> = emptyMap(),
    val type: String = "",
    val updatedAt: Timestamp? = null
)
