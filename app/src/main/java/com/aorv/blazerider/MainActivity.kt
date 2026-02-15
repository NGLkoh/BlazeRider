package com.aorv.blazerider

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private var lastKnownLocation: Location? = null // Track last significant location
    private var lastUpdate: Long = 0 // For debouncing Firestore updates
    private var inactivityJob: Job? = null // For 5-minute inactivity check
    private var backgroundTimestamp: Long? = null // Track background time
    private val LOCATION_PERMISSION_REQUEST_CODE = 1001
    private val NOTIFICATION_PERMISSION_REQUEST_CODE = 1007
    private val TAG = "MainActivity"
    private val MIN_DISTANCE_CHANGE = 10.0f // 10 meters threshold
    private val LOCATION_UPDATE_INTERVAL = 5000L // 5 seconds
    private val INACTIVITY_THRESHOLD = 5 * 60 * 1000L // 5 minutes

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Initialize Firebase and Location
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Request notification permission (Android 13+)
        requestNotificationPermission()

        // Add lifecycle observer for app foreground/background events
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> onAppForegrounded()
                Lifecycle.Event.ON_STOP -> onAppBackgrounded()
                else -> { /* No action needed */ }
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)

        // Synchronous check for user status immediately
        handleUserStatusCheck()
    }

    private fun handleUserStatusCheck() {
        val currentUser = auth.currentUser

        if (currentUser != null) {
            Log.d(TAG, "User is logged in, checking Firestore status: ${currentUser.email}")
            
            // Special handling for the hardcoded admin user ID
            if (currentUser.uid == "A7USXq3qwFgCH4sov6mmPdtaGOn2") {
                startActivity(Intent(this, AdminActivity::class.java))
                finish()
                return
            }

            // Setup presence and location listener
            setupPresenceAndLocation(currentUser.uid)

            db.collection("users").document(currentUser.uid).get()
                .addOnSuccessListener { document ->
                    if (document.exists()) {
                        // 1. CHECK FOR DEACTIVATION FIRST
                        val isDeactivated = document.getBoolean("deactivated") ?: false
                        if (isDeactivated) {
                            startActivity(Intent(this, AccountDeactivatedActivity::class.java))
                            finish()
                            return@addOnSuccessListener
                        }

                        // 2. CONTINUE WITH ROUTING LOGIC
                        val verified = document.getBoolean("verified") ?: false
                        val stepCompleted = document.getLong("stepCompleted")?.toInt() ?: 1
                        
                        // Handle admin check (supporting boolean, string, or long)
                        val isAdmin = when (val adminValue = document.get("admin")) {
                            is Boolean -> adminValue
                            is String -> adminValue.toBooleanStrictOrNull() ?: false
                            is Long -> adminValue == 1L
                            else -> false
                        }
                        
                        if (isAdmin) {
                            Log.d(TAG, "You are an admin, redirecting to AdminActivity")
                            startActivity(Intent(this, AdminActivity::class.java))
                        } else if (verified) {
                            Log.d(TAG, "User is verified, redirecting to HomeActivity")
                            startActivity(Intent(this, HomeActivity::class.java))
                            // Start location updates only for verified users after routing
                            startLocationUpdates(currentUser.uid)
                        } else {
                            Log.d(TAG, "User is not verified, redirecting based on stepCompleted: $stepCompleted")
                            val intent = when (stepCompleted) {
                                1 -> Intent(this, EmailVerificationActivity::class.java)
                                2 -> Intent(this, CurrentAddressActivity::class.java)
                                3 -> Intent(this, AdminApprovalActivity::class.java)
                                else -> Intent(this, EmailVerificationActivity::class.java)
                            }
                            startActivity(intent)
                        }
                    } else {
                        Log.w(TAG, "User document not found in Firestore, creating document")
                        createUserDocument(currentUser.uid, currentUser.email, currentUser.displayName)
                        startActivity(Intent(this, MainMenuActivity::class.java))
                    }
                    finish() // Finish MainActivity immediately after routing
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "Failed to fetch Firestore user data: ${exception.message}")
                    startActivity(Intent(this, MainMenuActivity::class.java))
                    finish()
                }
        } else {
            Log.d(TAG, "No user logged in, redirecting to MainMenuActivity")
            startActivity(Intent(this, MainMenuActivity::class.java))
            finish()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST_CODE
                )
            } else {
                Log.d(TAG, "Notification permission already granted.")
                getAndLogFCMToken()
            }
        } else {
            getAndLogFCMToken()
        }
    }

    private fun getAndLogFCMToken() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w(TAG, "Fetching FCM registration token failed", task.exception)
                return@addOnCompleteListener
            }
            val token = task.result
            Log.d(TAG, "FCM Token: $token")
        }
    }

    private fun createUserDocument(userId: String, email: String?, displayName: String?) {
        db.collection("users").document(userId).set(
            mapOf(
                "displayName" to (displayName ?: ""),
                "email" to (email ?: ""),
                "verified" to false,
                "stepCompleted" to 1,
                "lastActive" to FieldValue.serverTimestamp(),
                "state" to "offline",
                "location" to GeoPoint(0.0, 0.0)
            ),
            SetOptions.merge()
        ).addOnFailureListener { e ->
            Log.e(TAG, "Failed to create user document: ${e.message}")
        }
    }

    private fun checkLocationPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isGpsEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun requestLocationPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    auth.currentUser?.let { startLocationUpdates(it.uid) }
                } else {
                    auth.currentUser?.let {
                        updateUserStatus(it.uid, "offline", GeoPoint(0.0, 0.0))
                    }
                }
            }
            NOTIFICATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Notification permission granted")
                    getAndLogFCMToken()
                } else {
                    Log.w(TAG, "Notification permission denied")
                }
            }
        }
    }

    private fun updateUserStatus(userId: String, state: String, geoPoint: GeoPoint) {
        val status = mapOf(
            "state" to state,
            "lastActive" to FieldValue.serverTimestamp(),
            "location" to geoPoint
        )
        db.collection("users").document(userId)
            .set(status, SetOptions.merge())
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to update Firestore status: ${e.message}")
            }
    }

    private fun startLocationUpdates(userId: String) {
        if (!checkLocationPermissions()) {
            requestLocationPermissions()
            return
        }
        if (!isGpsEnabled()) {
            updateUserStatus(userId, "offline", GeoPoint(0.0, 0.0))
            Toast.makeText(this, "Please enable GPS for location updates", Toast.LENGTH_LONG).show()
            return
        }

        val locationRequest = LocationRequest.create().apply {
            interval = LOCATION_UPDATE_INTERVAL
            fastestInterval = LOCATION_UPDATE_INTERVAL / 2
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { newLocation ->
                    // Check for significant change (>10 meters)
                    val isSignificantChange = lastKnownLocation?.let { last ->
                        val distance = FloatArray(1)
                        Location.distanceBetween(
                            last.latitude, last.longitude,
                            newLocation.latitude, newLocation.longitude,
                            distance
                        )
                        distance[0] > MIN_DISTANCE_CHANGE
                    } ?: true // Update if no previous location

                    if (isSignificantChange) {
                        lastKnownLocation = newLocation
                        val geoPoint = GeoPoint(newLocation.latitude, newLocation.longitude)
                        updateUserStatus(userId, "online", geoPoint)
                    }
                }
            }
        }

        try {
            locationCallback?.let {
                fusedLocationClient.requestLocationUpdates(locationRequest, it, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting location updates: ${e.message}")
            updateUserStatus(userId, "offline", GeoPoint(0.0, 0.0))
        }
    }

    private fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            locationCallback = null
        }
    }

    private fun setupPresenceAndLocation(userId: String) {
        // Initialize state and location
        updateUserStatus(userId, "offline", GeoPoint(0.0, 0.0))
    }

    private fun onAppForegrounded() {
        auth.currentUser?.let { user ->
            if (checkLocationPermissions() && isGpsEnabled()) {
                startLocationUpdates(user.uid)
            } else {
                updateUserStatus(user.uid, "offline", GeoPoint(0.0, 0.0))
            }
            // Cancel inactivity check
            inactivityJob?.cancel()
            backgroundTimestamp = null
        }
    }

    private fun onAppBackgrounded() {
        auth.currentUser?.let { user ->
            stopLocationUpdates()
            val geoPoint = lastKnownLocation?.let { GeoPoint(it.latitude, it.longitude) } ?: GeoPoint(0.0, 0.0)
            updateUserStatus(user.uid, "offline", geoPoint)
            // Start 5-minute inactivity check
            backgroundTimestamp = System.currentTimeMillis()
            inactivityJob = CoroutineScope(Dispatchers.Main).launch {
                delay(INACTIVITY_THRESHOLD)
                val timeElapsed = System.currentTimeMillis() - (backgroundTimestamp ?: 0)
                if (timeElapsed >= INACTIVITY_THRESHOLD) {
                    updateUserStatus(user.uid, "inactive", geoPoint)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        inactivityJob?.cancel()
    }
}
