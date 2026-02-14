package com.aorv.blazerider

import java.util.Date

data class Post(
    val id: String,
    val userId: String,
    val content: String,
    val createdAt: Date?,
    val imageUris: List<String> = emptyList(),
    val reactionCount: Map<String, Long> = emptyMap(),
    val commentsCount: Long = 0,
    val admin: Boolean = false,
    val isScheduled: Boolean = false // This field is required for your new logic
)