package com.aorv.blazerider

import java.io.Serializable

data class JoinedUser(
    val userId: String,
    val name: String
) : Serializable