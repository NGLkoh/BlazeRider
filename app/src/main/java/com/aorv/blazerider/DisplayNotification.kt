package com.aorv.blazerider

/**
 * A data class representing the final, display-ready information for a notification item
 * in the RecyclerView. This decouples the adapter from the complexities of data fetching.
 */
data class DisplayNotification(
    val title: String,
    val message: String,
    var isRead: Boolean, // var is used to allow for immediate (optimistic) UI updates
    val original: Notification // A reference to the original Firestore model
)
