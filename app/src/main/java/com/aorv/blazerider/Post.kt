package com.aorv.blazerider

import java.util.Date

data class Post(
    val id: String = "",
    val userId: String = "",
    val content: String = "",
    val createdAt: Date? = null,
    val imageUris: List<String> = emptyList(),
    val reactionCount: Map<String, Long> = emptyMap(),
    val commentsCount: Long = 0,
    val admin: Boolean = false,
    val isScheduled: Boolean = false,
    val type: String = "",
    val rideId: String = "",
    val sharedRouteId: String = "" // Added to link post to the shared ride
)
