package com.aorv.blazerider

import java.util.Date

data class Comment(
    val id: String,
    val userId: String,
    val content: String,
    val createdAt: Date?,
    val imageUris: List<String> = emptyList(),
    val reactionCount: Map<String, Long> = mapOf(
        "angry" to 0L,
        "haha" to 0L,
        "like" to 0L,
        "love" to 0L,
        "sad" to 0L,
        "wow" to 0L
    ),
    val firstName: String,
    val lastName: String,
    val profileImageUrl: String
)