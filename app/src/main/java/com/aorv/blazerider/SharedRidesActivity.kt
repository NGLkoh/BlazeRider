package com.aorv.blazerider

import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import androidx.viewpager2.widget.ViewPager2
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale
import com.google.android.material.appbar.MaterialToolbar
import com.google.firebase.firestore.SetOptions

class SharedRidesActivity : AppCompatActivity() {
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var rideBanner: MaterialCardView
    private lateinit var rideMessage: android.widget.TextView
    private lateinit var trackRideButton: MaterialButton
    private lateinit var cancelRideButton: MaterialButton
    private var userListener: ListenerRegistration? = null
    private val TAG = "SharedRidesActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shared_rides)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        // Set up close button
        val toolbar = findViewById<MaterialToolbar>(R.id.top_app_bar)
        toolbar.setNavigationOnClickListener {
            finish()
        }

        // Initialize UI components
        rideBanner = findViewById(R.id.ride_banner)
        rideMessage = findViewById(R.id.ride_message)
        trackRideButton = findViewById(R.id.track_ride_button)
        cancelRideButton = findViewById(R.id.cancel_ride_button)

        // Set up ViewPager2 and TabLayout
        val viewPager = findViewById<ViewPager2>(R.id.view_pager)
        val tabLayout = findViewById<TabLayout>(R.id.tab_layout)
        viewPager.adapter = SharedRidesPagerAdapter(this)
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Shared Rides"
                1 -> "My Rides"
                else -> null
            }
        }.attach()

        // Check for active ride and display banner
        checkAndDisplayRideBanner()

        // Set up real-time listener for user document changes
        setupUserListener()

        // Handle Track Ride button
        trackRideButton.setOnClickListener {
            auth.currentUser?.uid?.let { userId ->
                db.collection("users").document(userId).get()
                    .addOnSuccessListener { userDoc ->
                        val currentJoinedRide = userDoc.getString("currentJoinedRide")
                        if (currentJoinedRide != null) {
                            fetchRideDetailsAndStartPreview(currentJoinedRide)
                        } else {
                            Log.w(TAG, "No active ride to track")
                            rideBanner.isVisible = false
                        }
                    }
            }
        }

        // Handle Cancel Ride button
        cancelRideButton.setOnClickListener {
            auth.currentUser?.uid?.let { userId ->
                // Show confirmation dialog
                android.app.AlertDialog.Builder(this)
                    .setTitle("Cancel Ride")
                    .setMessage("Are you sure you want to quit the ride?")
                    .setPositiveButton("Yes") { _, _ ->
                        db.collection("users").document(userId).get()
                            .addOnSuccessListener { userDoc ->
                                val currentJoinedRide = userDoc.getString("currentJoinedRide")
                                if (currentJoinedRide != null) {
                                    val role = userDoc.getString("role") ?: "customer"
                                    cancelRide(userId, currentJoinedRide, if (role == "rider") "completed" else "cancelled")
                                } else {
                                    Log.w(TAG, "No active ride to cancel")
                                    rideBanner.isVisible = false
                                    android.widget.Toast.makeText(this, "No active ride to cancel", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                            .addOnFailureListener { e ->
                                Log.e(TAG, "Error fetching user document: ${e.message}")
                                android.widget.Toast.makeText(this, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                    }
                    .setNegativeButton("No") { dialog, _ ->
                        dialog.dismiss()
                    }
                    .setCancelable(true)
                    .show()
            }
        }
    }

    private fun setupUserListener() {
        auth.currentUser?.uid?.let { userId ->
            userListener = db.collection("users").document(userId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Error listening to user document: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null && snapshot.exists()) {
                        Log.d(TAG, "User document changed, refreshing banner")
                        checkAndDisplayRideBanner()
                    }
                }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Remove the listener to prevent memory leaks
        userListener?.remove()
    }

    private fun cancelRide(userId: String, rideId: String, status: String) {
        db.runTransaction { transaction ->
            val userRef = db.collection("users").document(userId)
            val rideRef = db.collection("sharedRoutes").document(rideId)
            val rideDoc = transaction.get(rideRef)

            if (rideDoc.exists() && (rideDoc.getString("userUid") == userId || rideDoc.get("joinedRiders.$userId") != null)) {
                // Remove the user from joinedRiders
                transaction.update(rideRef, "joinedRiders.$userId", FieldValue.delete())
                // Clear currentJoinedRide in user document
                transaction.set(userRef, mapOf("currentJoinedRide" to null), SetOptions.merge())
            } else {
                throw Exception("User is not part of this ride")
            }
        }.addOnSuccessListener {
            Log.d(TAG, "Ride $rideId cancelled for user $userId")
            rideBanner.isVisible = false
            android.widget.Toast.makeText(this, "Ride cancelled", android.widget.Toast.LENGTH_SHORT).show()
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to cancel ride: ${e.message}")
            android.widget.Toast.makeText(this, "Cannot cancel ride: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAndDisplayRideBanner() {
        auth.currentUser?.uid?.let { userId ->
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val userDoc = db.collection("users").document(userId).get().await()
                    val currentJoinedRide = userDoc.getString("currentJoinedRide")
                    val userFirstName = userDoc.getString("firstName") ?: "User"
                    val role = userDoc.getString("role") ?: "customer"

                    Log.d(TAG, "Checking currentJoinedRide: $currentJoinedRide")
                    if (currentJoinedRide != null) {
                        val rideDoc = db.collection("sharedRoutes").document(currentJoinedRide).get().await()
                        if (rideDoc.exists() && (rideDoc.getString("userUid") == userId || rideDoc.getString("joinedRiders.$userId.status") == "confirmed")) {
                            val riderUid = rideDoc.getString("userUid") ?: ""
                            val riderName = if (riderUid == userId) {
                                "$userFirstName ${userDoc.getString("lastName") ?: ""}".trim()
                            } else {
                                val riderDoc = db.collection("users").document(riderUid).get().await()
                                "${riderDoc.getString("firstName") ?: "Rider"} ${riderDoc.getString("lastName") ?: ""}".trim()
                            }
                            val customerName = if (role == "customer") userFirstName else riderName

                            val origin = rideDoc.getString("origin") ?: "Unknown Origin"
                            val destination = rideDoc.getString("destination") ?: "Unknown Destination"
                            val message = if (role == "customer") {
                                "$userFirstName, your ride with $riderName from $origin to $destination is in progress."
                            } else {
                                "$userFirstName, you are driving $customerName from $origin to $destination."
                            }
                            rideMessage.text = message
                            rideBanner.isVisible = true
                            cancelRideButton.text = if (role == "rider") "End Ride" else "Cancel Ride"
                        } else {
                            rideBanner.isVisible = false
                        }
                    } else {
                        rideBanner.isVisible = false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error displaying ride banner: ${e.message}")
                    rideBanner.isVisible = false
                }
            }
        }
    }

    private fun fetchRideDetailsAndStartPreview(rideId: String) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val rideDoc = db.collection("sharedRoutes").document(rideId).get().await()
                if (rideDoc.exists()) {
                    val datetime = rideDoc.getTimestamp("datetime")?.toDate()?.time ?: 0L
                    val destination = rideDoc.getString("destination") ?: ""
                    val destinationCoordinates = rideDoc.get("destinationCoordinates") as? Map<String, Any>
                    val destinationLat = destinationCoordinates?.get("latitude") as? Double ?: 0.0
                    val destinationLng = destinationCoordinates?.get("longitude") as? Double ?: 0.0
                    val distance = rideDoc.getDouble("distance") ?: 0.0
                    val duration = rideDoc.getDouble("duration") ?: 0.0
                    val origin = rideDoc.getString("origin") ?: ""
                    val originCoordinates = rideDoc.get("originCoordinates") as? Map<String, Any>
                    val originLat = originCoordinates?.get("latitude") as? Double ?: 0.0
                    val originLng = originCoordinates?.get("longitude") as? Double ?: 0.0
                    val userUid = rideDoc.getString("userUid") ?: ""

                    val intent = Intent(this@SharedRidesActivity, PreviewRideActivity::class.java).apply {
                        putExtra("ride_datetime", datetime)
                        putExtra("ride_destination", destination)
                        putExtra("ride_destination_lat", destinationLat)
                        putExtra("ride_destination_lng", destinationLng)
                        putExtra("ride_distance", distance)
                        putExtra("ride_duration", duration)
                        putExtra("ride_origin", origin)
                        putExtra("ride_origin_lat", originLat)
                        putExtra("ride_origin_lng", originLng)
                        putExtra("ride_user_uid", userUid)
                        putExtra("ride_id", rideId)
                    }
                    startActivity(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching ride details: ${e.message}")
                android.widget.Toast.makeText(this@SharedRidesActivity, "Failed to load ride details", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private inner class SharedRidesPagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> SharedRidesFragment()
            1 -> MyRidesFragment()
            else -> throw IllegalStateException("Unexpected position $position")
        }
    }
}