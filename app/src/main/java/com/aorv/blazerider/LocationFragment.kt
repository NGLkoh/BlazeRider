package com.aorv.blazerider

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.firebase.geofire.GeoFire
import com.firebase.geofire.GeoLocation
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import com.google.maps.android.PolyUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

class LocationFragment : Fragment(), OnMapReadyCallback, GoogleMap.OnMapClickListener {
    private lateinit var map: GoogleMap
    private lateinit var auth: FirebaseAuth
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var searchPlaceholder: TextView
    private lateinit var searchBarButton: MaterialCardView
    private var currentMarker: Marker? = null
    private var currentLocation: LatLng? = null
    private var lastKnownLocation: LatLng? = null // Track last significant location
    private lateinit var bottomView: ConstraintLayout
    private lateinit var navigationBottomView: ConstraintLayout
    private lateinit var navigationInstructionsView: ConstraintLayout
    private lateinit var placeNameTextView: TextView
    private lateinit var placeAddressTextView: TextView
    private lateinit var tripDurationTextView: TextView
    private lateinit var tripDistanceTextView: TextView
    private var selectedPlaceName: String? = null
    private var selectedPlaceAddress: String? = null
    private var selectedPlaceLat: Double = 0.0
    private var selectedPlaceLng: Double = 0.0
    private val REQUEST_PERMISSION_CODE = 100
    private val SPEECH_REQUEST_CODE = 101
    private val SEARCH_ACTIVITY_REQUEST_CODE = 1000
    private val GPS_SETTINGS_REQUEST_CODE = 103
    private val okHttpClient = OkHttpClient()
    private val apiKey = "AIzaSyBdsslBjsFC919mvY0osI8hAmrPOzFp_LE" // Replace with your Google Maps API key
    private val firestore = FirebaseFirestore.getInstance()
    private lateinit var locationCallback: LocationCallback
    private val weatherApiKey = "69ab2179dbf3e9906f04f1a5c3eadec8"
    private val CHANNEL_ID = "location_notifications"
    private var isLocationUpdatesActive = false
    private val MIN_DISTANCE_CHANGE = 10.0f // 10 meters threshold
    private val LOCATION_UPDATE_INTERVAL = 10000L // 10 seconds
    private var stepPolylines: MutableList<String> = mutableListOf()

    // Variables to store route data
    private var routeInstructions: String? = null
    private var routeDuration: String? = null
    private var routeDistance: String? = null
    private var routeEta: String? = null
    private var routePolyline: String? = null
    private val START_ROUTE_ACTIVITY_REQUEST_CODE = 104
    private lateinit var geoFire: GeoFire // Added for GeoFire
    private lateinit var locationViewModel: LocationViewModel

    private var cityName: String = "Current Location" // Initialize with default value

    // Static flag to track if the welcome dialog has been shown in this app session
    companion object {
        private var hasShownWelcomeDialog = false
        private var hasFetchedWeather = false
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            enableMyLocation()
        } else {
            if (!ActivityCompat.shouldShowRequestPermissionRationale(requireActivity(), Manifest.permission.ACCESS_FINE_LOCATION)) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Permission Required")
                    .setMessage("Location permission is required to show your current location. Please enable it in app settings.")
                    .setPositiveButton("Go to Settings") { _, _ ->
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.fromParts("package", requireContext().packageName, null)
                        }
                        startActivity(intent)
                    }
                    .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                    .show()
            } else {
                Toast.makeText(requireContext(), "Location permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun enableMyLocation() {
        if (!isAdded() || !::map.isInitialized) {
            Log.w("LocationFragment", "Fragment not attached or map not initialized")
            return
        }

        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestLocationPermissions()
            return
        }

        // Only proceed with enabling location if GPS is enabled
        if (isGPSEnabled()) {
            try {
                map.isMyLocationEnabled = true
                if (!isLocationUpdatesActive) {
                    startLocationUpdates()
                }
            } catch (e: Exception) {
                Log.e("LocationFragment", "Error enabling location: ${e.message}", e)
                Toast.makeText(requireContext(), "Failed to enable location", Toast.LENGTH_SHORT).show()
            }
        } else {
            // GPS is disabled, but do not show dialog; just log and exit
            Log.w("LocationFragment", "GPS is disabled, skipping location enable")
        }
    }

    private fun startLocationUpdates() {
        if (!isAdded() || isLocationUpdatesActive || !::map.isInitialized) {
            Log.w("LocationFragment", "Cannot start location updates: Fragment not attached, updates active, or map not initialized")
            return
        }

        val locationRequest = LocationRequest.create().apply {
            interval = LOCATION_UPDATE_INTERVAL
            fastestInterval = LOCATION_UPDATE_INTERVAL / 2
            priority = Priority.PRIORITY_HIGH_ACCURACY
        }

        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
                    .addOnSuccessListener {
                        isLocationUpdatesActive = true
                        Log.d("LocationFragment", "Location updates started")
                    }
                    .addOnFailureListener { e ->
                        isLocationUpdatesActive = false
                        Log.e("LocationFragment", "Failed to start location updates: ${e.message}", e)
                        Toast.makeText(requireContext(), "Failed to get location updates", Toast.LENGTH_SHORT).show()
                    }
            } catch (e: Exception) {
                Log.e("LocationFragment", "Exception in starting location updates: ${e.message}", e)
                Toast.makeText(requireContext(), "Error starting location updates", Toast.LENGTH_SHORT).show()
            }
        } else {
            requestLocationPermissions()
        }
    }

    private fun stopLocationUpdates() {
        if (isLocationUpdatesActive) {
            try {
                fusedLocationClient.removeLocationUpdates(locationCallback)
                    .addOnSuccessListener {
                        isLocationUpdatesActive = false
                        Log.d("LocationFragment", "Location updates stopped")
                    }
                    .addOnFailureListener { e ->
                        Log.e("LocationFragment", "Failed to stop location updates: ${e.message}", e)
                    }
            } catch (e: Exception) {
                Log.e("LocationFragment", "Error stopping location updates: ${e.message}", e)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        initLocationCallback()
        // Initialize GeoFire
        val databaseRef = FirebaseDatabase.getInstance().reference.child("users")
        geoFire = GeoFire(databaseRef)
        // Initialize ViewModel
        locationViewModel = ViewModelProvider(requireActivity())[LocationViewModel::class.java]
    }

    private fun initLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: run {
                    Log.w("LocationFragment", "No location available in result")
                    return
                }
                if (!isAdded() || !::map.isInitialized) {
                    Log.w("LocationFragment", "Fragment not attached or map not initialized, ignoring location update")
                    return
                }
                try {
                    val newLocation = LatLng(location.latitude, location.longitude)
                    // Check for significant change
                    val isSignificantChange = lastKnownLocation?.let { last ->
                        val distance = FloatArray(1)
                        android.location.Location.distanceBetween(
                            last.latitude, last.longitude,
                            newLocation.latitude, newLocation.longitude,
                            distance
                        )
                        distance[0] > MIN_DISTANCE_CHANGE
                    } ?: true // Update if no previous location

                    if (isSignificantChange) {
                        currentLocation = newLocation
                        lastKnownLocation = newLocation
                        locationViewModel.updateLocation(newLocation) // Update ViewModel
                        map.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLocation!!, 15f))

                        // Update cityName from weather API only once per session
                        if (!hasFetchedWeather) {
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val weatherUrl = "https://api.openweathermap.org/data/2.5/weather?lat=${location.latitude}&lon=${location.longitude}&appid=$weatherApiKey&units=metric"
                                    val weatherRequest = Request.Builder().url(weatherUrl).build()
                                    val weatherResponse = okHttpClient.newCall(weatherRequest).execute()
                                    val weatherJson = weatherResponse.body?.string()

                                    Log.d("Weather", "Response for ${location.latitude}, ${location.longitude}: $weatherJson")

                                    if (!weatherResponse.isSuccessful) {
                                        throw Exception("Weather API error: ${weatherResponse.code} ${weatherResponse.message}")
                                    }

                                    if (weatherJson.isNullOrEmpty()) {
                                        throw Exception("Empty weather response")
                                    }

                                    val jsonObject = JSONObject(weatherJson)
                                    val weatherArray = jsonObject.getJSONArray("weather")
                                    if (weatherArray.length() == 0) {
                                        throw Exception("No weather data available")
                                    }

                                    val weather = weatherArray.getJSONObject(0)
                                    val weatherDescription = weather.getString("description").replaceFirstChar { it.uppercase() }
                                    val temperature = jsonObject.getJSONObject("main").getDouble("temp")
                                    cityName = jsonObject.getString("name").takeIf { it.isNotEmpty() } ?: "Current Location"
                                    Log.d("LocationFragment", "Updated cityName: $cityName")

                                    val message = "Location: $cityName (${location.latitude}, ${location.longitude})\nWeather: $weatherDescription, Temp: ${String.format("%.1f", temperature)}°C"

                                    withContext(Dispatchers.Main) {
                                        sendNotification("Weather Update", message)
                                        hasFetchedWeather = true
                                    }

                                    saveNotificationToFirestore(message, jsonObject, location.latitude, location.longitude)
                                } catch (e: Exception) {
                                    Log.e("Weather", "Error fetching weather: ${e.message}", e)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(requireContext(), "Failed to fetch city name: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                    cityName = "Current Location"
                                }
                            }
                        }

                        // Save location to Realtime Database
                        val userId = auth.currentUser?.uid ?: return
                        val locationData = mapOf(
                            "latitude" to location.latitude,
                            "longitude" to location.longitude,
                            "timestamp" to System.currentTimeMillis()
                        )
                        FirebaseDatabase.getInstance().reference
                            .child("users").child(userId).child("location")
                            .setValue(locationData)
                            .addOnSuccessListener {
                                Log.d("LocationFragment", "Location saved to users/$userId/location: $locationData")
                            }
                            .addOnFailureListener { e ->
                                Log.e("LocationFragment", "Failed to save location to users/$userId/location: ${e.message}")
                                Toast.makeText(requireContext(), "Failed to save location", Toast.LENGTH_SHORT).show()
                            }
                    }
                } catch (e: Exception) {
                    Log.e("LocationFragment", "Error processing location: ${e.message}", e)
                    Toast.makeText(requireContext(), "Error updating location", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_location, container, false)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize Firebase
        auth = FirebaseAuth.getInstance()

        // Initialize FusedLocationProviderClient
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())

        // Check if user is logged in
        val currentUser = auth.currentUser
        if (currentUser == null) {
            Toast.makeText(requireContext(), "No user logged in", Toast.LENGTH_SHORT).show()
            startActivity(Intent(requireContext(), SignUpActivity::class.java))
            requireActivity().overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
            requireActivity().finish()
            return
        }

        // Initialize user metadata (optional, set once if not already set)
        val userInfo = mapOf(
            "userInfo" to mapOf(
                "displayName" to (currentUser.displayName ?: "User"),
                "photoUrl" to (currentUser.photoUrl?.toString() ?: "")
            )
        )
        FirebaseDatabase.getInstance().reference
            .child("users").child(currentUser.uid)
            .updateChildren(userInfo)
            .addOnFailureListener { e ->
                Log.e("LocationFragment", "Failed to set user metadata: ${e.message}", e)
            }

        // Initialize status with pending if GPS is disabled
        if (!isGPSEnabled()) {
            val statusData = mapOf(
                "state" to "pending",
                "lastActive" to System.currentTimeMillis(),
                "location" to mapOf(
                    "latitude" to (lastKnownLocation?.latitude ?: 0.0),
                    "longitude" to (lastKnownLocation?.longitude ?: 0.0)
                )
            )
            FirebaseDatabase.getInstance().reference
                .child("status").child(currentUser.uid)
                .setValue(statusData)
                .addOnSuccessListener {
                    Log.d("LocationFragment", "Initial status set to pending: $statusData")
                }
                .addOnFailureListener { e ->
                    Log.e("LocationFragment", "Failed to set initial status: ${e.message}")
                }
        }
// Add this with your other view initializations in onViewCreated (around line 280)
        val gpsButton = view.findViewById<FloatingActionButton>(R.id.gps_button)

// GPS button click listener
        gpsButton.setOnClickListener {
            if (!isGPSEnabled()) {
                showGPSEnabledDialog()
                return@setOnClickListener
            }

            if (ActivityCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestLocationPermissions()
                return@setOnClickListener
            }

            // Get current location and animate to it
            getCurrentLocationAndAnimate()
        }


        // Adjust padding for status bar
        val searchBar = view.findViewById<View>(R.id.search_bar)
        ViewCompat.setOnApplyWindowInsetsListener(searchBar) { v, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, statusBarHeight + 16, v.paddingRight, v.paddingBottom)
            insets
        }

        // Initialize views
        searchBarButton = view.findViewById(R.id.search_bar_button)
        searchPlaceholder = view.findViewById(R.id.search_placeholder)
        val micButton = view.findViewById<ImageView>(R.id.mic_button)
        val userIcon = view.findViewById<ImageView>(R.id.user_icon)

        // Initialize bottom view
        bottomView = view.findViewById(R.id.bottom_view)
        placeNameTextView = view.findViewById(R.id.place_name)
        placeAddressTextView = view.findViewById(R.id.place_address)
        val closeButton = view.findViewById<ImageView>(R.id.close_button)
        val startRouteButton = view.findViewById<Button>(R.id.start_route_button)
        val shareRideButton = view.findViewById<Button>(R.id.share_ride_button)

        // Initialize navigation bottom view
        navigationBottomView = view.findViewById(R.id.navigation_bottom_view)
        val navCloseButton = view.findViewById<ImageView>(R.id.nav_close_button)
        val navStartButton = view.findViewById<Button>(R.id.nav_start_button)
        val navShareRideButton = view.findViewById<Button>(R.id.nav_share_ride_button)
        tripDurationTextView = view.findViewById(R.id.trip_duration)
        tripDistanceTextView = view.findViewById(R.id.trip_distance)

        // Initialize navigation instructions view
        navigationInstructionsView = view.findViewById(R.id.navigation_instructions_view)

        // Search bar click listener
        searchBarButton.setOnClickListener {
            val intent = Intent(requireContext(), SearchActivity::class.java)
            startActivityForResult(intent, SEARCH_ACTIVITY_REQUEST_CODE)
            requireActivity().overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }

        // Set up Shared Rides button click listener
//        view.findViewById<FloatingActionButton>(R.id.shared_rides_button).setOnClickListener {
//            val intent = Intent(requireContext(), SharedRidesActivity::class.java)
//            startActivity(intent)
//        }

        // Microphone button click listener for speech-to-text
        micButton.setOnClickListener {
            if (checkAudioPermission()) {
                startSpeechToText()
            } else {
                requestAudioPermission()
            }
        }

        // User icon click listener
        userIcon.setOnClickListener {
            val intent = Intent(requireContext(), ProfileMenuActivity::class.java)
            startActivity(intent)
            requireActivity().overridePendingTransition(R.anim.slide_in_bottom, R.anim.fade_out)
        }

        // Bottom view button listeners
        closeButton.setOnClickListener {
            clearMarker()
        }

        startRouteButton.setOnClickListener {
            if (!isGPSEnabled()) {
                Toast.makeText(requireContext(), "Please turn on your GPS", Toast.LENGTH_SHORT).show()
                showGPSEnabledDialog()
                return@setOnClickListener
            }
            if (currentLocation == null) {
                Toast.makeText(requireContext(), "Current location not available, retrying...", Toast.LENGTH_SHORT).show()
                startLocationUpdates()
                return@setOnClickListener
            }
            if (selectedPlaceLat == 0.0 || selectedPlaceLng == 0.0) {
                Toast.makeText(requireContext(), "Please select a destination", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                drawRouteForMotorcycle()
            }
        }

//        shareRideButton.setOnClickListener {
//            val placeName = placeNameTextView.text.toString()
//            Toast.makeText(context, "Share ride: $placeName", Toast.LENGTH_SHORT).show()
//            // TODO: Implement share ride logic
//        }

        // Navigation bottom view button listeners
        navCloseButton.setOnClickListener {
            navigationBottomView.visibility = View.GONE
            navigationInstructionsView.visibility = View.GONE
            bottomView.visibility = View.VISIBLE
            map.clear()
            if (selectedPlaceLat != 0.0 && selectedPlaceLng != 0.0) {
                val location = LatLng(selectedPlaceLat, selectedPlaceLng)
                currentMarker = map.addMarker(MarkerOptions().position(location).title(selectedPlaceName))
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(location, 15f))
            }
        }

        navStartButton.setOnClickListener {
            if (routeInstructions != null && routeDuration != null && routeDistance != null && routeEta != null && routePolyline != null) {
                val intent = Intent(requireContext(), StartRouteActivity::class.java).apply {
                    putExtra("instructions", routeInstructions)
                    putExtra("duration", routeDuration)
                    putExtra("distance", routeDistance)
                    putExtra("eta", routeEta)
                    putExtra("polyline", routePolyline)
                    putExtra("destination", LatLng(selectedPlaceLat, selectedPlaceLng)) // Add destination
                    putExtra("step_polylines", ArrayList(stepPolylines)) // Add step polylines
                    putExtra("destination_lat", selectedPlaceLat)
                    putExtra("destination_lng", selectedPlaceLng)
                }
                startActivityForResult(intent, START_ROUTE_ACTIVITY_REQUEST_CODE)
                selectedPlaceName?.let { logRideToHistory(it) }
            } else {
                Toast.makeText(requireContext(), "Route data not available", Toast.LENGTH_SHORT).show()
                // Optionally, trigger route calculation if not already done
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    drawRouteForMotorcycle()
                }
            }
        }

        navShareRideButton.setOnClickListener {
            val userId = auth.currentUser?.uid ?: run {
                Toast.makeText(requireContext(), "User not logged in", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (currentLocation == null || selectedPlaceName == null || routeDistance == null || routeDuration == null) {
                Toast.makeText(requireContext(), "Route or location data missing", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Fetch user data and check cityName
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    // If cityName is "Current Location", try reverse geocoding as fallback
                    val origin = if (cityName == "Current Location") {
                        Log.d("LocationFragment", "cityName not updated, using reverse geocoding for ${currentLocation!!.latitude}, ${currentLocation!!.longitude}")
                        reverseGeocodeFallback(currentLocation!!.latitude, currentLocation!!.longitude)
                    } else {
                        cityName
                    }

                    Log.d("LocationFragment", "Using origin: $origin")

                    // Fetch user data from Firestore
                    val document = firestore.collection("users").document(userId).get().await()
                    if (document.exists()) {
                        val firstName = document.getString("firstName") ?: ""
                        val lastName = document.getString("lastName") ?: ""
                        val userName = "$firstName $lastName".trim()

                        // Parse distance and duration
                        val distance = routeDistance?.replace(" km", "")?.toDoubleOrNull() ?: 0.0
                        val durationSeconds = parseDurationToSeconds(routeDuration!!)

                        // Prepare route data
                        val routeData = hashMapOf(
                            "datetime" to FieldValue.serverTimestamp(),
                            "destination" to selectedPlaceName,
                            "destinationCoordinates" to mapOf(
                                "latitude" to selectedPlaceLat,
                                "longitude" to selectedPlaceLng
                            ),
                            "distance" to distance,
                            "duration" to durationSeconds,
                            "origin" to origin,
                            "originCoordinates" to mapOf(
                                "latitude" to currentLocation!!.latitude,
                                "longitude" to currentLocation!!.longitude
                            ),
                            "userName" to userName,
                            "userUid" to userId
                        )

                        // Save to Firestore under sharedRoutes
                        firestore.collection("sharedRoutes").add(routeData)
                            .addOnSuccessListener {
                                // Show Toast and navigate to SharedRidesActivity
                                Toast.makeText(
                                    requireContext(),
                                    "Successfully shared ride from $origin to $selectedPlaceName",
                                    Toast.LENGTH_SHORT
                                ).show()
                                val intent = Intent(requireContext(), SharedRidesActivity::class.java)
                                startActivity(intent)
                            }
                            .addOnFailureListener { e ->
                                Toast.makeText(requireContext(), "Failed to share ride: ${e.message}", Toast.LENGTH_SHORT).show()
                                Log.e("LocationFragment", "Error saving shared route: ${e.message}", e)
                            }
                    } else {
                        Toast.makeText(requireContext(), "User data not found", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Failed to fetch user data or location: ${e.message}", Toast.LENGTH_SHORT).show()
                    Log.e("LocationFragment", "Error: ${e.message}", e)
                }
            }
        }

        // Initialize Google Map
        val mapFragment = childFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        // Show welcome dialog if not shown in this session
        if (!hasShownWelcomeDialog) {
            hasShownWelcomeDialog = true
            showWelcomeDialog()
        } else {
            initializeLocationLogic()
        }
    }

    // Simplified reverse geocoding fallback
    private suspend fun reverseGeocodeFallback(latitude: Double, longitude: Double): String {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            Log.e("LocationFragment", "Invalid coordinates: lat=$latitude, lng=$longitude")
            return "Current Location"
        }

        try {
            val url = "https://maps.googleapis.com/maps/api/geocode/json?latlng=$latitude,$longitude&key=$apiKey"
            val request = Request.Builder().url(url).build()
            val response = withContext(Dispatchers.IO) {
                okHttpClient.newCall(request).execute()
            }
            val json = response.body?.string()

            if (json != null && response.isSuccessful) {
                val jsonObject = JSONObject(json)
                val results = jsonObject.getJSONArray("results")
                if (results.length() > 0) {
                    val firstResult = results.getJSONObject(0)
                    if (firstResult.has("formatted_address")) {
                        val address = firstResult.getString("formatted_address")
                        if (address.isNotBlank()) return address
                    }
                    val addressComponents = firstResult.getJSONArray("address_components")
                    for (i in 0 until addressComponents.length()) {
                        val component = addressComponents.getJSONObject(i)
                        val types = component.getJSONArray("types")
                        for (j in 0 until types.length()) {
                            if (types.getString(j) == "locality" || types.getString(j) == "administrative_area_level_1") {
                                val shortName = component.getString("short_name")
                                if (shortName.isNotBlank()) return shortName
                            }
                        }
                    }
                    Log.w("LocationFragment", "No suitable address found in geocoding response")
                    return "Current Location"
                } else {
                    Log.w("LocationFragment", "No results in geocoding response")
                    return "Current Location"
                }
            } else {
                Log.e("LocationFragment", "Geocoding API response empty or failed: ${response.code}")
                return "Current Location"
            }
        } catch (e: IOException) {
            Log.e("LocationFragment", "Network error in reverse geocoding: ${e.message}", e)
            return "Current Location"
        } catch (e: JSONException) {
            Log.e("LocationFragment", "Error parsing geocoding response: ${e.message}", e)
            return "Current Location"
        } catch (e: Exception) {
            Log.e("LocationFragment", "Unexpected error in reverse geocoding: ${e.message}", e)
            return "Current Location"
        }
    }

    // Parse duration string (e.g., "25 min" or "1 hr 30 min") to seconds
    private fun parseDurationToSeconds(duration: String): Int {
        try {
            return when {
                duration.contains("hr") -> {
                    val parts = duration.split(" ")
                    val hours = parts[0].toIntOrNull() ?: 0
                    val minutes = parts.getOrNull(2)?.replace("min", "")?.toIntOrNull() ?: 0
                    hours * 3600 + minutes * 60
                }
                duration.contains("min") -> {
                    duration.replace("min", "").trim().toIntOrNull()?.times(60) ?: 0
                }
                else -> 0
            }
        } catch (e: Exception) {
            Log.e("LocationFragment", "Error parsing duration '$duration': ${e.message}", e)
            return 0
        }
    }

    private fun logRideToHistory(destination: String) {
        val userId = auth.currentUser?.uid ?: return
        val historyRef = firestore.collection("users").document(userId).collection("history")

        val historyEntry = hashMapOf(
            "message" to "Started a ride to $destination",
            "timestamp" to FieldValue.serverTimestamp()
        )

        historyRef.add(historyEntry)
            .addOnSuccessListener {
                Log.d("LocationFragment", "Successfully logged ride to history.")
            }
            .addOnFailureListener { e ->
                Log.e("LocationFragment", "Error logging ride to history", e)
            }
    }

    private fun initializeLocationLogic() {
        if (isGPSEnabled() && ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            enableMyLocation()
        }
        // If GPS is disabled, do not show dialog; just log and proceed
        Log.w("LocationFragment", "GPS is disabled in initializeLocationLogic, skipping dialog")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Location Updates"
            val descriptionText = "Notifications for location and GPS status"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun sendNotification(title: String, message: String) {
        val notificationManager = requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val builder = NotificationCompat.Builder(requireContext(), CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_blaze_rider)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    override fun onMapReady(googleMap: GoogleMap) {
        Log.d("LocationFragment", "Map ready")
        map = googleMap
        val defaultLocation = LatLng(14.5995, 120.9842)
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLocation, 10f))
        map.setOnMapClickListener(this)
        map.uiSettings.isMyLocationButtonEnabled = false

        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            Log.d("LocationFragment", "Location permission granted, enabling location")
            enableMyLocation()
        } else {
            Log.d("LocationFragment", "Requesting location permission")
            requestLocationPermissions()
        }
    }

    override fun onMapClick(point: LatLng) {
        // Allow map interactions without affecting bottom view
    }

    private fun requestLocationPermissions() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED -> {
                enableMyLocation()
            }
            ActivityCompat.shouldShowRequestPermissionRationale(
                requireActivity(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) -> {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Location Permission Needed")
                    .setMessage("This app needs access to your location to show your current position on the map.")
                    .setPositiveButton("OK") { _, _ ->
                        permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                    .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                    .show()
            }
            else -> {
                permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    private fun updateCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestLocationPermissions()
            return
        }
        if (isGPSEnabled()) {
            startLocationUpdates()
        } else {
            // GPS is disabled, but do not show dialog; just log
            Log.w("LocationFragment", "GPS is disabled in updateCurrentLocation, skipping dialog")
        }
    }

    private fun fetchWeatherAndSendNotification(lat: Double, lng: Double) {
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) {
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(requireContext(), "Invalid coordinates: ($lat, $lng)", Toast.LENGTH_SHORT).show()
            }
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("Weather", "Fetching weather for lat: $lat, lng: $lng")
                val weatherUrl = "https://api.openweathermap.org/data/2.5/weather?lat=$lat&lon=$lng&appid=$weatherApiKey&units=metric"
                val weatherRequest = Request.Builder().url(weatherUrl).build()
                val weatherResponse = okHttpClient.newCall(weatherRequest).execute()
                val weatherJson = weatherResponse.body?.string()

                Log.d("Weather", "Response: $weatherJson")

                if (!weatherResponse.isSuccessful) {
                    throw Exception("Weather API error: ${weatherResponse.code} ${weatherResponse.message}")
                }

                if (weatherJson.isNullOrEmpty()) {
                    throw Exception("Empty weather response")
                }

                val jsonObject = JSONObject(weatherJson)
                val weatherArray = jsonObject.getJSONArray("weather")
                if (weatherArray.length() == 0) {
                    throw Exception("No weather data available")
                }

                val weather = weatherArray.getJSONObject(0)
                val weatherDescription = weather.getString("description").replaceFirstChar { it.uppercase() }
                val temperature = jsonObject.getJSONObject("main").getDouble("temp")
                val cityName = jsonObject.getString("name").takeIf { it.isNotEmpty() } ?: "Unknown location"

                val message = "Location: $cityName ($lat, $lng)\nWeather: $weatherDescription, Temp: ${String.format("%.1f", temperature)}°C"

                withContext(Dispatchers.Main) {
                    sendNotification("Weather Update", message)
                }

                saveNotificationToFirestore(message, jsonObject, lat, lng)
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Network error: ${e.message}", Toast.LENGTH_SHORT).show()
                    Log.e("Weather", "Network error: ${e.message}", e)
                }
            } catch (e: JSONException) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error parsing weather data: ${e.message}", Toast.LENGTH_SHORT).show()
                    Log.e("Weather", "JSON error: ${e.message}", e)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error fetching weather: ${e.message}", Toast.LENGTH_SHORT).show()
                    Log.e("Weather", "General error: ${e.message}", e)
                }
            }
        }
    }

    private fun saveNotificationToFirestore(message: String, jsonObject: JSONObject, lat: Double, lng: Double) {
        val userId = auth.currentUser?.uid ?: return
        val weather = jsonObject.getJSONArray("weather").getJSONObject(0)
        val weatherMain = weather.getString("main")
        val weatherDescription = weather.getString("description").replaceFirstChar { it.uppercase() }
        val temperature = jsonObject.getJSONObject("main").getDouble("temp")
        val cityName = jsonObject.getString("name").takeIf { it.isNotEmpty() } ?: "Unknown location"

        val notification = hashMapOf(
            "type" to "weather",
            "actorId" to null,
            "entityType" to "weather",
            "entityId" to null,
            "message" to message,
            "metadata" to mapOf(
                "weather" to weatherMain,
                "weatherDescription" to weatherDescription,
                "temperature" to temperature,
                "cityName" to cityName,
                "location" to GeoPoint(lat, lng)
            ),
            "isRead" to false,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        firestore.collection("users").document(userId).collection("notifications")
            .add(notification)
            .addOnFailureListener {
                Toast.makeText(requireContext(), "Failed to save notification to Firestore", Toast.LENGTH_SHORT).show()
                Log.e("Firestore", "Error saving notification: ${it.message}", it)
            }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun drawRouteForMotorcycle() {
        if (currentLocation == null || selectedPlaceLat == 0.0 || selectedPlaceLng == 0.0) {
            Toast.makeText(requireContext(), "Location data missing", Toast.LENGTH_SHORT).show()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val origin = "${currentLocation!!.latitude},${currentLocation!!.longitude}"
                val destination = "$selectedPlaceLat,$selectedPlaceLng"
                val url = "https://maps.googleapis.com/maps/api/directions/json?" +
                        "origin=$origin" +
                        "&destination=$destination" +
                        "&mode=driving" +
                        "&key=$apiKey"

                val request = Request.Builder().url(url).build()
                val response = okHttpClient.newCall(request).execute()
                val json = response.body?.string()

                if (json != null) {
                    val jsonObject = JSONObject(json)
                    val routes = jsonObject.getJSONArray("routes")
                    if (routes.length() == 0) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "No routes found", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }

                    val route = routes.getJSONObject(0)
                    val legs = route.getJSONArray("legs")
                    val leg = legs.getJSONObject(0)

                    val overviewPolyline = route.getJSONObject("overview_polyline")
                    val points = overviewPolyline.getString("points")
                    val decodedPoints = PolyUtil.decode(points)

                    val polylinePoints = decodedPoints.map { "${it.latitude},${it.longitude}" }
                    val polylineString = polylinePoints.joinToString("|")

                    val distanceMeters = leg.getJSONObject("distance").getInt("value") / 1000.0
                    val durationSeconds = leg.getJSONObject("duration").getInt("value")
                    val durationMinutes = durationSeconds / 60
                    val durationText = formatDuration(durationMinutes)
                    val distanceText = String.format("%.1f km", distanceMeters)

                    val steps = leg.getJSONArray("steps")
                    val directionsList = mutableListOf<String>()
                    val stepPolylines = mutableListOf<String>() // Store encoded polylines for each step
                    for (i in 0 until steps.length()) {
                        val step = steps.getJSONObject(i)
                        val instruction = step.getString("html_instructions").replace("<[^>]*>".toRegex(), "")
                        val distance = step.getJSONObject("distance").getString("text")
                        directionsList.add("$instruction ($distance)")
                        // Get step polyline
                        val stepPolyline = step.getJSONObject("polyline").getString("points")
                        stepPolylines.add(stepPolyline)
                    }
                    val directionsText = directionsList.joinToString("\n")

                    val currentTime = java.time.LocalTime.now()
                    val etaTime = currentTime.plusMinutes(durationMinutes.toLong())
                    val etaText = etaTime.format(java.time.format.DateTimeFormatter.ofPattern("hh:mm a"))

                    withContext(Dispatchers.Main) {
                        map.addPolyline(
                            PolylineOptions()
                                .addAll(decodedPoints)
                                .color(Color.BLUE)
                                .width(10f)
                                .geodesic(true)
                        )
                        tripDurationTextView.text = durationText
                        tripDistanceTextView.text = "($distanceText)"

                        routeInstructions = directionsText
                        routeDuration = durationText
                        routeDistance = distanceText
                        routeEta = etaText
                        routePolyline = polylineString
                        // Store destination for rerouting
                        val destination = LatLng(selectedPlaceLat, selectedPlaceLng)
                        // Pass step polylines to StartRouteActivity
                        val stepPolylinesArray = stepPolylines.toTypedArray()

                        val startRouteButton = view?.findViewById<Button>(R.id.start_route_button)

                        // Update the intent in navStartButton click listener
                        startRouteButton?.setOnClickListener {
                            if (routeInstructions != null && routeDuration != null && routeDistance != null && routeEta != null && routePolyline != null) {
                                val intent = Intent(requireContext(), StartRouteActivity::class.java).apply {
                                    putExtra("instructions", routeInstructions)
                                    putExtra("duration", routeDuration)
                                    putExtra("distance", routeDistance)
                                    putExtra("eta", routeEta)
                                    putExtra("polyline", routePolyline)
                                    putExtra("destination", destination) // Add destination
                                    putExtra("step_polylines", stepPolylinesArray) // Add step polylines
                                }
                                startActivityForResult(intent, START_ROUTE_ACTIVITY_REQUEST_CODE)
                            } else {
                                Toast.makeText(requireContext(), "Route data not available", Toast.LENGTH_SHORT).show()
                            }
                        }

                        bottomView.visibility = View.GONE
                        navigationBottomView.visibility = View.VISIBLE
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Empty response from Directions API", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Network error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: JSONException) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error parsing Directions API response: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error fetching route: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun snapToRoadsAndDrawRoute() {
        if (currentLocation == null || selectedPlaceLat == 0.0 || selectedPlaceLng == 0.0) {
            Toast.makeText(requireContext(), "Location data missing", Toast.LENGTH_SHORT).show()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val path = "${currentLocation!!.latitude},${currentLocation!!.longitude}|$selectedPlaceLat,$selectedPlaceLng"
                val url = "https://roads.googleapis.com/v1/snapToRoads?path=$path&interpolate=true&key=$apiKey"
                val request = Request.Builder().url(url).build()
                val response = okHttpClient.newCall(request).execute()
                val json = response.body?.string()

                if (json != null) {
                    val jsonObject = JSONObject(json)
                    val snappedPoints = jsonObject.getJSONArray("snappedPoints")
                    val points = mutableListOf<LatLng>()
                    for (i in 0 until snappedPoints.length()) {
                        val point = snappedPoints.getJSONObject(i).getJSONObject("location")
                        val lat = point.getDouble("latitude")
                        val lng = point.getDouble("longitude")
                        points.add(LatLng(lat, lng))
                    }

                    val distanceKm = calculateDistance(currentLocation!!, LatLng(selectedPlaceLat, selectedPlaceLng))
                    val durationMin = estimateDuration(distanceKm)
                    val durationText = formatDuration(durationMin)
                    val distanceText = String.format("%.1f km", distanceKm)

                    withContext(Dispatchers.Main) {
                        map.addPolyline(
                            PolylineOptions()
                                .addAll(points)
                                .color(Color.BLUE)
                                .width(10f)
                                .geodesic(true)
                        )
                        tripDurationTextView.text = durationText
                        tripDistanceTextView.text = "($distanceText)"
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Empty response from Roads API", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Network error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error snapping to roads: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun calculateDistance(start: LatLng, end: LatLng): Float {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            start.latitude, start.longitude,
            end.latitude, end.longitude,
            results
        )
        return results[0] / 1000
    }

    private fun estimateDuration(distanceKm: Float): Int {
        val speedKmh = 40.0f
        val hours = distanceKm / speedKmh
        return (hours * 60).toInt()
    }

    private fun formatDuration(minutes: Int): String {
        return when {
            minutes < 60 -> "$minutes min"
            else -> {
                val hours = minutes / 60
                val remainingMin = minutes % 60
                if (remainingMin == 0) "$hours hr" else "$hours hr $remainingMin min"
            }
        }
    }

    private fun checkAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAudioPermission() {
        ActivityCompat.requestPermissions(
            requireActivity(),
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_PERMISSION_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startSpeechToText()
                } else {
                    Toast.makeText(requireContext(), "Microphone permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startSpeechToText() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your search...")
        }
        try {
            startActivityForResult(intent, SPEECH_REQUEST_CODE)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Speech recognition not supported", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isGPSEnabled(): Boolean {
        return try {
            val locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) {
            Log.e("LocationFragment", "Error checking GPS status: ${e.message}", e)
            false
        }
    }

    private fun showGPSEnabledDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("GPS Required")
            .setMessage("Please enable GPS to access your current location.")
            .setPositiveButton("Enable GPS") { _, _ ->
                val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                startActivityForResult(intent, GPS_SETTINGS_REQUEST_CODE)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            START_ROUTE_ACTIVITY_REQUEST_CODE -> {
                navigationBottomView.visibility = View.VISIBLE
            }
            SPEECH_REQUEST_CODE -> {
                if (resultCode == Activity.RESULT_OK) {
                    data?.let {
                        val results = it.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                        results?.firstOrNull()?.let { text ->
                            searchPlaceholder.text = text
                            val intent = Intent(requireContext(), SearchActivity::class.java).apply {
                                putExtra("SEARCH_QUERY", text)
                            }
                            startActivityForResult(intent, SEARCH_ACTIVITY_REQUEST_CODE)
                            requireActivity().overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
                        }
                    }
                }
            }
            SEARCH_ACTIVITY_REQUEST_CODE -> {
                if (resultCode == Activity.RESULT_OK) {
                    data?.let {
                        val placeName = it.getStringExtra("SEARCH_QUERY") ?: return@let
                        val placeAddress = it.getStringExtra("PLACE_ADDRESS") ?: ""
                        val placeLat = it.getDoubleExtra("PLACE_LAT", 0.0)
                        val placeLng = it.getDoubleExtra("PLACE_LNG", 0.0)

                        selectedPlaceName = placeName
                        selectedPlaceAddress = placeAddress
                        selectedPlaceLat = placeLat
                        selectedPlaceLng = placeLng

                        searchPlaceholder.text = placeName

                        if (placeLat != 0.0 && placeLng != 0.0) {
                            val location = LatLng(placeLat, placeLng)
                            currentMarker?.remove()
                            currentMarker = map.addMarker(MarkerOptions().position(location).title(placeName))
                            map.animateCamera(CameraUpdateFactory.newLatLngZoom(location, 15f))

                            placeNameTextView.text = placeName
                            placeAddressTextView.text = placeAddress
                            bottomView.visibility = View.VISIBLE
                        }
                    }
                }
            }
            GPS_SETTINGS_REQUEST_CODE -> {
                if (isGPSEnabled()) {
                    enableMyLocation()
                } else {
                    Toast.makeText(requireContext(), "GPS is still disabled", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun clearMarker() {
        map.clear()
        currentMarker?.remove()
        currentMarker = null

        bottomView.visibility = View.GONE
        navigationBottomView.visibility = View.GONE
        navigationInstructionsView.visibility = View.GONE
        searchPlaceholder.text = "Your location"

        selectedPlaceName = null
        selectedPlaceAddress = null
        selectedPlaceLat = 0.0
        selectedPlaceLng = 0.0
        routeInstructions = null
        routeDuration = null
        routeDistance = null
        routeEta = null
        routePolyline = null
        tripDurationTextView.text = ""
        tripDistanceTextView.text = ""

        if (isGPSEnabled() && ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            enableMyLocation()
        } else {
            val defaultLocation = LatLng(14.5995, 120.9842)
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(defaultLocation, 10f))
        }
    }

    override fun onPause() {
        super.onPause()
        stopLocationUpdates()
        // Set status to offline with last known location
        val userId = auth.currentUser?.uid ?: return
        val statusData = mapOf(
            "state" to "offline",
            "lastActive" to System.currentTimeMillis(),
            "location" to mapOf(
                "latitude" to (lastKnownLocation?.latitude ?: 0.0),
                "longitude" to (lastKnownLocation?.longitude ?: 0.0)
            )
        )
        FirebaseDatabase.getInstance().reference
            .child("status").child(userId)
            .setValue(statusData)
            .addOnFailureListener { e ->
                Log.e("LocationFragment", "Failed to set offline status: ${e.message}")
            }
    }

    override fun onResume() {
        super.onResume()
        if (isGPSEnabled() && ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            enableMyLocation()
        } else {
            // GPS is disabled, but do not show dialog; just log
            Log.w("LocationFragment", "GPS is disabled in onResume, skipping dialog")
        }

        // Show welcome dialog only if not shown in this session
        if (!hasShownWelcomeDialog) {
            hasShownWelcomeDialog = true // Mark dialog as shown
            showWelcomeDialog()
        }
    }

    private fun showWelcomeDialog() {
        // Create spannable string for bold title and regular subtitle
        val title = "Welcome to Blaze Rider\n"
        val subtitle = "Fuel Your Passion, Blaze Your Own Trail."
        val fullText = SpannableString(title + subtitle)
        fullText.setSpan(
            android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
            0,
            title.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        fullText.setSpan(
            AbsoluteSizeSpan(24, true), // 24sp for title
            0,
            title.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        fullText.setSpan(
            AbsoluteSizeSpan(14, true), // 14sp for subtitle
            title.length,
            fullText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // Create custom layout for dialog
        val customLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        // Load and tint the icon
        val icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_blaze_rider)?.apply {
            DrawableCompat.setTint(this, ContextCompat.getColor(requireContext(), android.R.color.black))
        } ?: run {
            Log.e("LocationFragment", "Failed to load ic_blaze_rider drawable")
            ContextCompat.getDrawable(requireContext(), android.R.drawable.ic_dialog_info)?.apply {
                DrawableCompat.setTint(this, ContextCompat.getColor(requireContext(), android.R.color.black))
            }
        }

        // Create ImageView for the icon
        val iconView = ImageView(requireContext()).apply {
            setImageDrawable(icon)
            val size = (200 * resources.displayMetrics.density).toInt() // 230dp
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER
                bottomMargin = (8 * resources.displayMetrics.density).toInt() // 8dp spacing below icon
            }
        }

        // Create TextView for message
        val messageTextView = TextView(requireContext()).apply {
            text = fullText
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
        }

        // Add views to custom layout
        customLayout.addView(iconView)
        customLayout.addView(messageTextView)

        // Build the dialog
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(customLayout)
            .setPositiveButton("Next") { _, _ ->
                initializeLocationLogic() // Start location logic after dialog dismissal
            }
            .setCancelable(false)
            .create()

        // Show the dialog and customize the button
        dialog.show()

        // Customize the "Next" button (center, set color)
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.let { button ->
            button.setTextColor(ContextCompat.getColor(requireContext(), R.color.red_orange))
            val layoutParams = button.layoutParams as? LinearLayout.LayoutParams
            layoutParams?.gravity = Gravity.CENTER
            button.layoutParams = layoutParams
        }
    }

    private fun getCurrentLocationAndAnimate() {
        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestLocationPermissions()
            return
        }

        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                if (location != null) {
                    val currentLatLng = LatLng(location.latitude, location.longitude)
                    currentLocation = currentLatLng
                    lastKnownLocation = currentLatLng
                    locationViewModel.updateLocation(currentLatLng)

                    // Animate camera to current location with smooth transition
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(currentLatLng, 17f),
                        500,
                        null
                    )

                    Toast.makeText(
                        requireContext(),
                        "Centered on your location",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    // If lastLocation is null, request fresh location
                    Toast.makeText(
                        requireContext(),
                        "Getting your location...",
                        Toast.LENGTH_SHORT
                    ).show()
                    requestFreshLocation()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    requireContext(),
                    "Failed to get location: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
                Log.e("LocationFragment", "Error getting location: ${e.message}", e)
            }
    }

    private fun requestFreshLocation() {
        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val locationRequest = LocationRequest.create().apply {
            priority = Priority.PRIORITY_HIGH_ACCURACY
            numUpdates = 1
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { location ->
                        val currentLatLng = LatLng(location.latitude, location.longitude)
                        currentLocation = currentLatLng
                        lastKnownLocation = currentLatLng
                        locationViewModel.updateLocation(currentLatLng)

                        map.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(currentLatLng, 17f),
                            500,
                            null
                        )

                        Toast.makeText(
                            requireContext(),
                            "Location updated",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    fusedLocationClient.removeLocationUpdates(this)
                }
            },
            Looper.getMainLooper()
        )
    }
}
