package com.aorv.blazerider

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.datatransport.BuildConfig
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.navigation.NavigationApi
import com.google.android.libraries.navigation.NavigationView
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.SimulationOptions
import com.google.android.libraries.navigation.Waypoint
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.util.concurrent.TimeUnit

class StartRouteActivity : AppCompatActivity() {
    private var mNavigator: Navigator? = null
    private lateinit var navView: NavigationView
    private var arrivalListener: Navigator.ArrivalListener? = null
    private var routeChangedListener: Navigator.RouteChangedListener? = null
    private val firestore = FirebaseFirestore.getInstance()
    private var userId: String? = null
    private var destinationName: String? = null
    private var destinationLatLng: LatLng? = null


    var navigatorScope: InitializedNavScope? = null
    var pendingNavActions = mutableListOf<InitializedNavRunnable>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Obtain a reference to the NavigationView
        setContentView(R.layout.activity_start_route)
        navView = findViewById(R.id.navigation_view)
        navView.onCreate(savedInstanceState)

        // Enable the trip progress bar (instructions bar)
        navView.setTripProgressBarEnabled(true)

        // Ensure the screen stays on during navigation
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.navigation_view)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Retrieve destination coordinates from Intent
        val destinationLat = intent.getDoubleExtra("destination_lat", 0.0)
        val destinationLng = intent.getDoubleExtra("destination_lng", 0.0)
        destinationLatLng = LatLng(destinationLat, destinationLng)
        destinationName = intent.getStringExtra("destination_name")
        userId = intent.getStringExtra("user_id")


        // Configure app permissions
        val permissions =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

        if (permissions.any { !checkPermissionGranted(it) }) {
            if (permissions.any { shouldShowRequestPermissionRationale(it) }) {
                // Display a dialogue explaining the required permissions
            }

            val permissionsLauncher =
                registerForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { permissionResults ->
                    if (permissionResults.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false)) {
                        onLocationPermissionGranted(destinationLatLng!!)
                    } else {
                        finish()
                    }
                }

            permissionsLauncher.launch(permissions)
        } else {
            android.os.Handler(Looper.getMainLooper()).postDelayed(
                { onLocationPermissionGranted(destinationLatLng!!) },
                TimeUnit.SECONDS.toMillis(2)
            )
        }
    }

    private fun checkPermissionGranted(permissionToCheck: String): Boolean =
        ContextCompat.checkSelfPermission(this, permissionToCheck) == PackageManager.PERMISSION_GRANTED

    private fun onLocationPermissionGranted(destination: LatLng) {
        initializeNavigationApi(destination)
    }

    /** Starts the Navigation API, capturing a reference when ready. */
    @SuppressLint("MissingPermission")
    private fun initializeNavigationApi(destination: LatLng) {
        NavigationApi.getNavigator(
            this,
            object : NavigationApi.NavigatorListener {
                override fun onNavigatorReady(navigator: Navigator) {
                    val scope = InitializedNavScope(navigator)
                    navigatorScope = scope
                    pendingNavActions.forEach { block -> scope.block() }
                    pendingNavActions.clear()
                    // Disables the guidance notifications and shuts down the app and background service
                    // when the user dismisses/swipes away the app from Android's recent tasks
                    navigator.setTaskRemovedBehavior(Navigator.TaskRemovedBehavior.QUIT_SERVICE)

                    mNavigator = navigator

                    if (BuildConfig.DEBUG) {
                        mNavigator?.simulator?.setUserLocation(startLocation)
                    }
                    // Listen for events en route
                    registerNavigationListeners()

                    navView.getMapAsync { googleMap ->
                        googleMap.followMyLocation(GoogleMap.CameraPerspective.TILTED)
                    }

                    // Navigate to the passed destination
                    navigateToPlace(destination)
                }

                override fun onError(@NavigationApi.ErrorCode errorCode: Int) {
                    when (errorCode) {
                        NavigationApi.ErrorCode.NOT_AUTHORIZED -> {
                            showToast(
                                "Error loading Navigation API: Your API key is " +
                                        "invalid or not authorized to use Navigation."
                            )
                        }
                        NavigationApi.ErrorCode.TERMS_NOT_ACCEPTED -> {
                            showToast(
                                "Error loading Navigation API: User did not " +
                                        "accept the Navigation Terms of Use."
                            )
                        }
                        else -> showToast("Error loading Navigation API: $errorCode")
                    }
                }
            },
        )
    }

    /**
     * Requests directions from the user's current location to the specified destination.
     */
    private fun navigateToPlace(destination: LatLng) {
        val waypoint: Waypoint =
            try {
                Waypoint.builder()
                    .setLatLng(destination.latitude, destination.longitude)
                    .setTitle("Destination")
                    .build()
            } catch (e: Exception) {
                showToast("Error creating waypoint: ${e.message}")
                return
            }

        withNavigatorAsync {
            val pendingRoute = mNavigator?.setDestination(waypoint)

            // Set an action to perform when a route is determined to the destination
            pendingRoute?.setOnResultListener { code ->
                when (code) {
                    Navigator.RouteStatus.OK -> {
                        // Hide the toolbar to maximize the navigation UI
                        supportActionBar?.hide()

                        // Enable voice audio guidance (through the device speaker)
                        mNavigator?.setAudioGuidance(Navigator.AudioGuidance.VOICE_ALERTS_AND_GUIDANCE)

                        // Simulate vehicle progress along the route (for demo/debug builds)
                        if (BuildConfig.DEBUG) {
                            mNavigator?.simulator?.simulateLocationsAlongExistingRoute(
                                SimulationOptions().speedMultiplier(5f)
                            )
                        }

                        // Start turn-by-turn guidance along the current route
                        mNavigator?.startGuidance()
                    }

                    Navigator.RouteStatus.ROUTE_CANCELED -> showToast("Route guidance cancelled.")
                    Navigator.RouteStatus.NO_ROUTE_FOUND,
                    Navigator.RouteStatus.NETWORK_ERROR ->
                        showToast("Error starting guidance: $code")

                    else -> showToast("Error starting guidance: $code")
                }
            }
        }
    }

    private fun logRideHistory(status: String) {
        if (userId == null) {
            Log.e("StartRouteActivity", "User ID is null, cannot log history")
            return
        }
        val rideData = mutableMapOf<String, Any>(
            "status" to status,
            "timestamp" to FieldValue.serverTimestamp()
        )
        destinationName?.let {
            rideData["destination"] = it
        }
        destinationLatLng?.let {
            rideData["destinationCoordinates"] = mapOf("latitude" to it.latitude, "longitude" to it.longitude)
        }
        firestore.collection("users").document(userId!!).collection("history")
            .add(rideData)
            .addOnSuccessListener {
                Log.d("StartRouteActivity", "Ride history logged: $status")
            }
            .addOnFailureListener { e ->
                Log.e("StartRouteActivity", "Failed to log ride history: ${e.message}", e)
            }
    }


    /**
     * Registers event listeners for navigation events.
     */
    private fun registerNavigationListeners() {
        withNavigatorAsync {
            arrivalListener =
                Navigator.ArrivalListener {
                    showToast("User has arrived at the destination!")
                    logRideHistory("Arrived")
                    mNavigator?.clearDestinations()

                    // Stop simulating vehicle movement
                    if (BuildConfig.DEBUG) {
                        mNavigator?.simulator?.unsetUserLocation()
                    }
                }
            mNavigator?.addArrivalListener(arrivalListener)

            routeChangedListener =
                Navigator.RouteChangedListener {
                    showToast("onRouteChanged: the driver's route changed")
                }
            mNavigator?.addRouteChangedListener(routeChangedListener)
        }
    }

    companion object {
        val startLocation = LatLng(14.5804, 120.9751) // Manila, Philippines as start location
    }

    /**
     * Runs [block] once navigator is initialized.
     */
    private fun withNavigatorAsync(block: InitializedNavRunnable) {
        val navigatorScope = navigatorScope
        if (navigatorScope != null) {
            navigatorScope.block()
        } else {
            pendingNavActions.add(block)
        }
    }

    override fun onSaveInstanceState(savedInstanceState: Bundle) {
        super.onSaveInstanceState(savedInstanceState)
        navView.onSaveInstanceState(savedInstanceState)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        navView.onTrimMemory(level)
    }

    override fun onStart() {
        super.onStart()
        navView.onStart()
    }

    override fun onResume() {
        super.onResume()
        navView.onResume()
    }

    override fun onPause() {
        navView.onPause()
        super.onPause()
    }

    override fun onConfigurationChanged(configuration: Configuration) {
        super.onConfigurationChanged(configuration)
        navView.onConfigurationChanged(configuration)
    }

    override fun onStop() {
        navView.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        navView.onDestroy()
        withNavigatorAsync {
            if (arrivalListener != null) {
                navigator.removeArrivalListener(arrivalListener)
            }
            if (routeChangedListener != null) {
                navigator.removeRouteChangedListener(routeChangedListener)
            }
            navigator.simulator?.unsetUserLocation()
            navigator.cleanup()
        }
        super.onDestroy()
    }

    private fun showToast(errorMessage: String) {
        Toast.makeText(this@StartRouteActivity, errorMessage, Toast.LENGTH_LONG).show()
    }
}

open class InitializedNavScope(val navigator: Navigator)

typealias InitializedNavRunnable = InitializedNavScope.() -> Unit