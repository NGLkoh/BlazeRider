package com.aorv.blazerider

import com.google.firebase.Timestamp

data class RideHistory(
    val datetime: Timestamp? = null,
    val destination: String? = null,
    val distance: Double? = null,
    val duration: Double? = null,
    val origin: String? = null,
    val status: String? = null,
    val userUid: String? = null,
    val sharedRoutesId: String? = null
)
