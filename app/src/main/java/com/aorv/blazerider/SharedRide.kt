package com.aorv.blazerider

import com.google.firebase.Timestamp

data class SharedRide(
    val datetime: Timestamp? = null,
    val destination: String? = null,
    val destinationCoordinates: Map<String, Double>? = null,
    val distance: Double? = null,
    val duration: Double? = null,
    val origin: String? = null,
    val originCoordinates: Map<String, Double>? = null,
    val userUid: String? = null,
    val joinedRiders: Map<String, Map<String, Any>>? = null,
    val sharedRoutesId: String? = null,
    val status: String? = null
)
