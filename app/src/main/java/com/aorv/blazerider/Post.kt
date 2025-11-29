package com.aorv.blazerider

import java.util.Date

data class Post(
    val id: String,
    val userId: String,
    val content: String,
    val createdAt: Date?,
    val imageUris: List<String>,
    val reactionCount: Map<String, Long>,
    val commentsCount: Long = 0,
    val admin: Boolean = false
)
