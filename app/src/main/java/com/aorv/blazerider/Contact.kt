package com.aorv.blazerider

import java.io.Serializable

data class Contact(
    val id: String,
    val firstName: String,
    val lastName: String,
    val profileImageUrl: String?,
    val email: String? = null,
    val lastActive: String? = null
) : Serializable
