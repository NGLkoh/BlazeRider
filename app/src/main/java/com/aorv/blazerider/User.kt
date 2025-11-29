package com.aorv.blazerider

import com.google.firebase.firestore.IgnoreExtraProperties

@IgnoreExtraProperties
data class User(
    val firstName: String? = null,
    val lastName: String? = null,
    val profileImageUrl: String? = null,
    val gender: String = "others",
    var verified: Boolean = false,
    val email: String? = null
)
