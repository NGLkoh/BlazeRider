package com.aorv.blazerider

import com.google.firebase.Timestamp

data class Chat(
    val chatId: String,
    val name: String,
    val type: String,
    val lastMessage: String?,
    val lastMessageTimestamp: Timestamp?,
    val createdAt: Timestamp?,
    val profileImage: String? = null,
    val contact: Contact? = null, // Add for p2p chats
    var unreadCount: Int = 0
)
