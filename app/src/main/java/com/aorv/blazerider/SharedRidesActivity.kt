package com.aorv.blazerider

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.OnBackPressedCallback
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
import com.google.android.material.appbar.MaterialToolbar
import com.google.firebase.firestore.SetOptions
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

class SharedRidesActivity : AppCompatActivity() {
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var rideBanner: MaterialCardView
    private lateinit var rideMessage: android.widget.TextView
    private lateinit var trackRideButton: MaterialButton
    private lateinit var cancelRideButton: MaterialButton
    private var userListener: ListenerRegistration? = null
    private var rideListener: ListenerRegistration? = null
    private var currentListeningRideId: String? = null
    private var bannerJob: kotlinx.coroutines.Job? = null
    private val TAG = "SharedRidesActivity"
    private lateinit var viewPager: ViewPager2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shared_rides)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        // Set up close button to trigger back navigation
        val toolbar = findViewById<MaterialToolbar>(R.id.top_app_bar)
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        // Handle back press to always redirect to HomeActivity
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val intent = Intent(this@SharedRidesActivity, HomeActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                startActivity(intent)
                finish()
            }
        })

        // Initialize UI components
        rideBanner = findViewById(R.id.ride_banner)
        rideMessage = findViewById(R.id.ride_message)
        trackRideButton = findViewById(R.id.track_ride_button)
        cancelRideButton = findViewById(R.id.cancel_ride_button)

        // Set up ViewPager2 and TabLayout
        viewPager = findViewById(R.id.view_pager)
        val tabLayout = findViewById<TabLayout>(R.id.tab_layout)
        viewPager.adapter = SharedRidesPagerAdapter(this)
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Shared Rides"
                1 -> "My Rides"
                else -> null
            }
        }.attach()

        // Handle tab selection from intent
        val selectedTab = intent.getIntExtra("SELECT_TAB", 0)
        viewPager.setCurrentItem(selectedTab, false)

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
                            fetchRideDetailsAndStartNavigation(currentJoinedRide)
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
                db.collection("users").document(userId).get()
                    .addOnSuccessListener { userDoc ->
                        val currentJoinedRide = userDoc.getString("currentJoinedRide")
                        if (currentJoinedRide != null) {
                            db.collection("sharedRoutes").document(currentJoinedRide).get()
                                .addOnSuccessListener { rideDoc ->
                                    val isHost = rideDoc.getString("userUid") == userId
                                    val title = if (isHost) "Cancel Ride" else "Quit Ride"
                                    val message = if (isHost) "Are you sure you want to cancel your ride? This will remove it for everyone." else "Are you sure you want to quit this ride?"

                                    android.app.AlertDialog.Builder(this)
                                        .setTitle(title)
                                        .setMessage(message)
                                        .setPositiveButton("Yes") { _, _ ->
                                            cancelRide(userId, currentJoinedRide)
                                        }
                                        .setNegativeButton("No", null)
                                        .show()
                                }
                        } else {
                            rideBanner.isVisible = false
                        }
                    }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val selectedTab = intent.getIntExtra("SELECT_TAB", 0)
        viewPager.setCurrentItem(selectedTab, false)
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
                        checkAndDisplayRideBanner(snapshot)
                    }
                }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        userListener?.remove()
        rideListener?.remove()
    }

    private fun cancelRide(userId: String, rideId: String) {
        db.runTransaction { transaction ->
            val userRef = db.collection("users").document(userId)
            val rideRef = db.collection("sharedRoutes").document(rideId)
            val rideDoc = transaction.get(rideRef)

            if (rideDoc.exists()) {
                val hostUid = rideDoc.getString("userUid")
                if (hostUid == userId) {
                    // Host is cancelling the entire ride
                    transaction.update(rideRef, "status", "cancelled")
                } else {
                    // Joiner is leaving the ride
                    transaction.update(rideRef, "joinedRiders.$userId", FieldValue.delete())
                }
                // Clear currentJoinedRide in user document for this user
                transaction.update(userRef, "currentJoinedRide", null)
            } else {
                // If ride doesn't exist, just clear user's status
                transaction.update(userRef, "currentJoinedRide", null)
            }
        }.addOnSuccessListener {
            Log.d(TAG, "Ride $rideId handled for user $userId")
            hideRideBanner()
            android.widget.Toast.makeText(this, "Ride updated", android.widget.Toast.LENGTH_SHORT).show()
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to update ride: ${e.message}")
            android.widget.Toast.makeText(this, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun refreshRideBanner() {
        checkAndDisplayRideBanner()
    }

    fun hideRideBanner() {
        bannerJob?.cancel()
        rideBanner.isVisible = false
    }

    private fun checkAndDisplayRideBanner(userSnapshot: com.google.firebase.firestore.DocumentSnapshot? = null) {
        bannerJob?.cancel()
        auth.currentUser?.uid?.let { userId ->
            bannerJob = CoroutineScope(Dispatchers.Main).launch {
                try {
                    val userDoc = userSnapshot ?: db.collection("users").document(userId).get().await()
                    val currentJoinedRide = userDoc.getString("currentJoinedRide")
                    val userFirstName = userDoc.getString("firstName") ?: "User"

                    if (currentJoinedRide.isNullOrEmpty()) {
                        rideListener?.remove()
                        currentListeningRideId = null
                        rideBanner.isVisible = false
                        return@launch
                    }

                    if (currentJoinedRide == currentListeningRideId && rideListener != null) {
                        return@launch
                    }

                    rideListener?.remove()
                    currentListeningRideId = currentJoinedRide

                    rideListener = db.collection("sharedRoutes").document(currentJoinedRide)
                        .addSnapshotListener { rideDoc, error ->
                            if (error != null) {
                                Log.e(TAG, "Error listening to ride: ${error.message}")
                                return@addSnapshotListener
                            }

                            if (rideDoc == null || !rideDoc.exists()) {
                                db.collection("users").document(userId).update("currentJoinedRide", null)
                                rideBanner.isVisible = false
                                return@addSnapshotListener
                            }

                            val status = rideDoc.getString("status")
                            val rideTime = rideDoc.getTimestamp("datetime")?.toDate()?.time ?: 0L
                            val now = System.currentTimeMillis()
                            val twentyFourHoursAgo = now - (24 * 60 * 60 * 1000L)
                            val isExpired = rideTime < twentyFourHoursAgo && status != "completed" && status != "cancelled"

                            if (status == "completed" || status == "cancelled" || status == "expired" || isExpired) {
                                db.collection("users").document(userId).update("currentJoinedRide", null)
                                rideBanner.isVisible = false
                                return@addSnapshotListener
                            }

                            val isPublic = rideDoc.getBoolean("isPublic") ?: true
                            if (!isPublic) {
                                rideBanner.isVisible = false
                                return@addSnapshotListener
                            }
                            
                            val hostUid = rideDoc.getString("userUid") ?: ""
                            val isHost = hostUid == userId
                            val isOngoing = status == "ongoing"
                            
                            val destination = rideDoc.getString("destination") ?: "Unknown Destination"
                            val sdf = SimpleDateFormat("MMM dd, yyyy 'at' hh:mm a", Locale.getDefault())
                            val formattedTime = sdf.format(Date(rideTime))

                            CoroutineScope(Dispatchers.Main).launch {
                                val message = if (isHost) {
                                    val joinedRiders = rideDoc.get("joinedRiders") as? Map<String, Any>
                                    val ridersCount = joinedRiders?.size ?: 0
                                    if (isOngoing) {
                                        if (ridersCount > 0) "$userFirstName, your ride to $destination is in progress with $ridersCount rider(s) joined."
                                        else "$userFirstName, your ride to $destination is active and awaiting riders."
                                    } else {
                                        "$userFirstName, your ride to $destination is scheduled for $formattedTime."
                                    }
                                } else {
                                    val hostDoc = db.collection("users").document(hostUid).get().await()
                                    val hostName = "${hostDoc.getString("firstName") ?: "Rider"} ${hostDoc.getString("lastName") ?: ""}".trim()
                                    if (isOngoing) {
                                        "$userFirstName, your ride with $hostName to $destination is in progress."
                                    } else {
                                        "$userFirstName, your ride with $hostName to $destination is scheduled for $formattedTime."
                                    }
                                }
                                rideMessage.text = message
                            }
                            
                            rideBanner.isVisible = true
                            
                            // Only allow tracking/starting if it's ongoing or host starting at the right time
                            trackRideButton.isVisible = true
                            if (isOngoing) {
                                trackRideButton.isEnabled = true
                                trackRideButton.text = if (isHost) "Continue Route" else "Track Ride"
                            } else if (isHost && now >= rideTime) {
                                trackRideButton.isEnabled = true
                                trackRideButton.text = "Start Route"
                            } else {
                                // Scheduled case - disable button for everyone until host starts it
                                trackRideButton.isEnabled = false
                                trackRideButton.text = if (isHost) "Start Route" else "Track Ride"
                            }

                            cancelRideButton.text = if (isHost) "Cancel Ride" else "Quit Ride"
                        }
                } catch (e: Exception) {
                    Log.e(TAG, "Error displaying ride banner: ${e.message}")
                    rideBanner.isVisible = false
                }
            }
        }
    }

    private fun fetchRideDetailsAndStartNavigation(rideId: String) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val rideDoc = db.collection("sharedRoutes").document(rideId).get().await()
                if (rideDoc.exists()) {
                    val status = rideDoc.getString("status")
                    val hostUid = rideDoc.getString("userUid")
                    
                    if (hostUid == auth.currentUser?.uid && status != "ongoing") {
                        db.collection("sharedRoutes").document(rideId).update("status", "ongoing")
                    }

                    val ride = rideDoc.toObject(SharedRide::class.java)?.copy(sharedRoutesId = rideId)
                    if (ride != null) {
                        val intent = Intent(this@SharedRidesActivity, InAppNavigationActivity::class.java).apply {
                            putExtra("EXTRA_RIDE", ride)
                        }
                        startActivity(intent)
                    }
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
