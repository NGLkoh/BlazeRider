package com.aorv.blazerider

import com.google.firebase.Timestamp

data class UserRequest(
    var userId: String? = null,
    var email: String? = null,
    var firstName: String? = null,
    var lastName: String? = null,
    var address: String? = null,
    var barangay: String? = null,
    var city: String? = null,
    var province: String? = null,
    var birthdate: Timestamp? = null,
    var gender: String? = null,
    var lastActive: Timestamp? = null,
    var profileImageUrl: String? = null,
    var fcmToken: String? = null,
    var state: String? = null,
    var currentJoinedRide: Any? = null, // Using Any? for null in Firestore
    var location: Any? = null, // Using Any? for geopoint
    var isVerified: Boolean = false,
    var isAdmin: Boolean = false,
    var isVerifiedRecent: Boolean = false,

    // --- NEW FIELDS ADDED HERE ---
    var deactivated: Boolean = false,
    var deactivationReason: String? = null

) {
    // Default constructor required for Firestore
    constructor() : this(
        userId = null,
        firstName = null,
        lastName = null,
        email = null,
        address = null,
        barangay = null,
        city = null,
        province = null,
        birthdate = null,
        gender = null,
        lastActive = null,
        profileImageUrl = null,
        fcmToken = null,
        state = null,
        currentJoinedRide = null,
        location = null,
        isVerified = false,
        isAdmin = false,
        isVerifiedRecent = false,

        // Initialize new fields
        deactivated = false,
        deactivationReason = null
    )
}