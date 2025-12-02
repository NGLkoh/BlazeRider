package com.aorv.blazerider

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId

data class RideHistory(
    @DocumentId val documentId: String = "",
    val datetime: Timestamp? = null,
    val destination: String? = null,
    val distance: Double? = null,
    val duration: Double? = null,
    val origin: String? = null,
    val status: String? = null,
    val userUid: String? = null,
    val sharedRoutesId: String? = null
)
