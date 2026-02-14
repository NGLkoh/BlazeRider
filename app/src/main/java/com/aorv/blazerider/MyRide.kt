package com.aorv.blazerider

import com.google.firebase.Timestamp
import java.util.Date

data class MyRide(
    val id: String? = null, // Document ID
    val rideName: String? = null,
    val description: String? = null,
    val rideTimestamp: Long? = null, // The actual date and time of the ride
    val startLocationName: String? = null,
    val startLocationAddress: String? = null,
    val startLat: Double? = null,
    val startLng: Double? = null,
    val endLocationName: String? = null,
    val endLocationAddress: String? = null,
    val endLat: Double? = null,
    val endLng: Double? = null,
    val hostId: String? = null,
    val createdAt: Date? = null, // The date/time it was scheduled to be created/published (as a Date to match Firestore)
    val distance: Double? = null,
    val duration: Double? = null,
    val isScheduled: Boolean = false, // True if awaiting publishing to sharedRoutes
    val originalSharedRouteId: String? = null // Link to sharedRoutes if published
)
