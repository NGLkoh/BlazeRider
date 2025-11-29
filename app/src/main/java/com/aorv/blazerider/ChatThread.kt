package com.aorv.blazerider

data class ChatThread(
    val chatId: String,
    val name: String, // Group name or contact name for individual chats
    val imageUrl: String?, // Group image or contact profile image
    val lastMessage: String?,
    val timestamp: String?,
    val unreadCount: Int,
    val isGroup: Boolean
)