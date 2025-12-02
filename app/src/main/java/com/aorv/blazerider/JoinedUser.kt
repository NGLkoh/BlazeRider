package com.aorv.blazerider

import java.io.Serializable

data class JoinedUser(
    val userId: String,
    val name: String,
    val profilePictureUrl: String? = null
) : Serializable