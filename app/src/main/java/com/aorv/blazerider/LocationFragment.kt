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
import com.google.firebase.firestore.Query
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
import java.util.concurrent.TimeUnit

class LocationFragment : Fragment(), OnMapReadyCallback, GoogleMap.OnMapClickListener {
    private lateinit var map: GoogleMap
    private lateinit var auth: FirebaseAuth
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var searchPlaceholder: TextView
    private lateinit var searchBarButton: MaterialCardView
    private var currentMarker: Marker? = null
    private var currentLocation: LatLng? = null
    private var lastKnownLocation: LatLng? = null
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
    private val apiKey = "AIzaSyBdsslBjsFC919mvY0osI8hAmrPOzFp_LE"
    private val firestore = FirebaseFirestore.getInstance()
    private lateinit var locationCallback: LocationCallback
    private val weatherApiKey = "69ab2179dbf3e9906f04f1a5c3eadec8"
    private val CHANNEL_ID = "location_notifications"
    private var isLocationUpdatesActive = false
    private val MIN_DISTANCE_CHANGE = 10.0f
    private val LOCATION_UPDATE_INTERVAL = 10000L
    private var stepPolylines: MutableList<String> = mutableListOf()

    private var routeInstructions: String? = null
    private var routeDuration: String? = null
    private var routeDistance: String? = null
    private var routeEta: String? = null
    private var routePolyline: String? = null
    private val START_ROUTE_ACTIVITY_REQUEST_CODE = 104
    private lateinit var geoFire: GeoFire
    private lateinit var locationViewModel: LocationViewModel

    private var currentCityName: String = "Current Location"

    companion object {
        private var hasShownWelcomeDialog = false
        private const val WEATHER_NOTIFICATION_COOLDOWN_MS = 3600000L // 1 hour
        private const val WEATHER_DISTANCE_THRESHOLD_METERS = 5000f // 5 km
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) enableMyLocation()
        else Toast.makeText(requireContext(), "Location permission denied", Toast.LENGTH_SHORT).show()
    }

    private fun enableMyLocation() {
        if (!isAdded() || !::map.isInitialized) return
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestLocationPermissions()
            return
        }
        if (isGPSEnabled()) {
            map.isMyLocationEnabled = true
            if (!isLocationUpdatesActive) startLocationUpdates()
        }
    }

    private fun startLocationUpdates() {
        if (!isAdded() || isLocationUpdatesActive || !::map.isInitialized) return
        val locationRequest = LocationRequest.create().apply {
            interval = LOCATION_UPDATE_INTERVAL
            fastestInterval = LOCATION_UPDATE_INTERVAL / 2
            priority = Priority.PRIORITY_HIGH_ACCURACY
        }
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            isLocationUpdatesActive = true
        }
    }

    private fun stopLocationUpdates() {
        if (isLocationUpdatesActive) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            isLocationUpdatesActive = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        initLocationCallback()
        geoFire = GeoFire(FirebaseDatabase.getInstance().reference.child("users"))
        locationViewModel = ViewModelProvider(requireActivity())[LocationViewModel::class.java]
    }

    private fun initLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: return
                if (!isAdded() || !::map.isInitialized) return
                
                val newLocation = LatLng(location.latitude, location.longitude)
                val isSignificantChange = lastKnownLocation?.let { last ->
                    val distance = FloatArray(1)
                    android.location.Location.distanceBetween(last.latitude, last.longitude, newLocation.latitude, newLocation.longitude, distance)
                    distance[0] > MIN_DISTANCE_CHANGE
                } ?: true

                if (isSignificantChange) {
                    currentLocation = newLocation
                    lastKnownLocation = newLocation
                    locationViewModel.updateLocation(newLocation)
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(newLocation, 15f))

                    checkAndFetchWeather(location.latitude, location.longitude)

                    val userId = auth.currentUser?.uid ?: return
                    val locationData = mapOf("latitude" to location.latitude, "longitude" to location.longitude, "timestamp" to System.currentTimeMillis())
                    FirebaseDatabase.getInstance().reference.child("users").child(userId).child("location").setValue(locationData)
                }
            }
        }
    }

    private fun checkAndFetchWeather(lat: Double, lng: Double) {
        val userId = auth.currentUser?.uid ?: return
        
        firestore.collection("users").document(userId).collection("notifications")
            .whereEqualTo("type", "weather")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(1)
            .get()
            .addOnSuccessListener { snapshot ->
                var shouldUpdate = true
                if (!snapshot.isEmpty) {
                    val lastNotif = snapshot.documents[0]
                    val createdAt = lastNotif.getTimestamp("createdAt")?.toDate()?.time ?: 0L
                    val metadata = lastNotif.get("metadata") as? Map<*, *>
                    val lastLocation = metadata?.get("location") as? GeoPoint
                    
                    val timeDiff = System.currentTimeMillis() - createdAt
                    
                    val distance = if (lastLocation != null) {
                        val res = FloatArray(1)
                        android.location.Location.distanceBetween(lat, lng, lastLocation.latitude, lastLocation.longitude, res)
                        res[0]
                    } else Float.MAX_VALUE

                    // Only update if 1 hour has passed OR user has moved more than 5km
                    shouldUpdate = timeDiff > WEATHER_NOTIFICATION_COOLDOWN_MS || distance > WEATHER_DISTANCE_THRESHOLD_METERS
                }

                if (shouldUpdate) {
                    fetchWeatherAndSendNotification(lat, lng)
                }
            }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_location, container, false)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        auth = FirebaseAuth.getInstance()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())

        val currentUser = auth.currentUser
        if (currentUser == null) {
            startActivity(Intent(requireContext(), SignUpActivity::class.java))
            requireActivity().finish()
            return
        }

        view.findViewById<FloatingActionButton>(R.id.gps_button).setOnClickListener {
            if (!isGPSEnabled()) showGPSEnabledDialog()
            else getCurrentLocationAndAnimate()
        }

        ViewCompat.setOnApplyWindowInsetsListener(view.findViewById(R.id.search_bar)) { v, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, statusBarHeight + 16, v.paddingRight, v.paddingBottom)
            insets
        }

        searchBarButton = view.findViewById(R.id.search_bar_button)
        searchPlaceholder = view.findViewById(R.id.search_placeholder)
        bottomView = view.findViewById(R.id.bottom_view)
        placeNameTextView = view.findViewById(R.id.place_name)
        placeAddressTextView = view.findViewById(R.id.place_address)
        navigationBottomView = view.findViewById(R.id.navigation_bottom_view)
        navigationInstructionsView = view.findViewById(R.id.navigation_instructions_view)
        tripDurationTextView = view.findViewById(R.id.trip_duration)
        tripDistanceTextView = view.findViewById(R.id.trip_distance)

        searchBarButton.setOnClickListener {
            startActivityForResult(Intent(requireContext(), SearchActivity::class.java), SEARCH_ACTIVITY_REQUEST_CODE)
        }

        view.findViewById<ImageView>(R.id.mic_button).setOnClickListener {
            if (checkAudioPermission()) startSpeechToText() else requestAudioPermission()
        }

        view.findViewById<ImageView>(R.id.user_icon).setOnClickListener {
            startActivity(Intent(requireContext(), ProfileMenuActivity::class.java))
        }

        view.findViewById<ImageView>(R.id.close_button).setOnClickListener { clearMarker() }
        view.findViewById<Button>(R.id.start_route_button).setOnClickListener {
            if (!isGPSEnabled()) showGPSEnabledDialog()
            else if (currentLocation != null) drawRouteForMotorcycle()
        }

        view.findViewById<ImageView>(R.id.nav_close_button).setOnClickListener {
            navigationBottomView.visibility = View.GONE
            navigationInstructionsView.visibility = View.GONE
            bottomView.visibility = View.VISIBLE
            map.clear()
            selectedPlaceName?.let { name ->
                val loc = LatLng(selectedPlaceLat, selectedPlaceLng)
                currentMarker = map.addMarker(MarkerOptions().position(loc).title(name))
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(loc, 15f))
            }
        }

        view.findViewById<Button>(R.id.nav_start_button).setOnClickListener {
            if (routeInstructions != null) {
                val intent = Intent(requireContext(), StartRouteActivity::class.java).apply {
                    putExtra("instructions", routeInstructions); putExtra("duration", routeDuration)
                    putExtra("distance", routeDistance); putExtra("eta", routeEta)
                    putExtra("polyline", routePolyline); putExtra("destination", LatLng(selectedPlaceLat, selectedPlaceLng))
                    putExtra("step_polylines", ArrayList(stepPolylines)); putExtra("destination_lat", selectedPlaceLat); putExtra("destination_lng", selectedPlaceLng)
                }
                startActivityForResult(intent, START_ROUTE_ACTIVITY_REQUEST_CODE)
                selectedPlaceName?.let { logRideToHistory(it) }
            }
        }

        view.findViewById<Button>(R.id.nav_share_ride_button).setOnClickListener {
            val userId = auth.currentUser?.uid ?: return@setOnClickListener
            if (currentLocation == null || selectedPlaceName == null) return@setOnClickListener
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val originAddress = reverseGeocodeFallback(currentLocation!!.latitude, currentLocation!!.longitude)
                    val document = firestore.collection("users").document(userId).get().await()
                    if (document.exists()) {
                        val name = "${document.getString("firstName")} ${document.getString("lastName")}".trim()
                        val routeData = hashMapOf(
                            "datetime" to FieldValue.serverTimestamp(), "destination" to selectedPlaceName,
                            "destinationCoordinates" to mapOf("latitude" to selectedPlaceLat, "longitude" to selectedPlaceLng),
                            "distance" to (routeDistance?.replace(" km", "")?.toDoubleOrNull() ?: 0.0),
                            "duration" to parseDurationToSeconds(routeDuration!!), "origin" to originAddress,
                            "originCoordinates" to mapOf("latitude" to currentLocation!!.latitude, "longitude" to currentLocation!!.longitude),
                            "userName" to name, "userUid" to userId
                        )
                        firestore.collection("sharedRoutes").add(routeData).addOnSuccessListener {
                            Toast.makeText(requireContext(), "Ride shared successfully", Toast.LENGTH_SHORT).show()
                            startActivity(Intent(requireContext(), SharedRidesActivity::class.java))
                        }
                    }
                } catch (e: Exception) { Log.e("LocationFragment", "Error sharing ride", e) }
            }
        }

        (childFragmentManager.findFragmentById(R.id.map) as SupportMapFragment).getMapAsync(this)
        if (!hasShownWelcomeDialog) { hasShownWelcomeDialog = true; showWelcomeDialog() }
    }

    private suspend fun reverseGeocodeFallback(lat: Double, lng: Double): String {
        return try {
            val url = "https://maps.googleapis.com/maps/api/geocode/json?latlng=$lat,$lng&key=$apiKey"
            val response = withContext(Dispatchers.IO) { okHttpClient.newCall(Request.Builder().url(url).build()).execute() }
            val json = response.body?.string()
            if (json != null) {
                val results = JSONObject(json).getJSONArray("results")
                if (results.length() > 0) results.getJSONObject(0).getString("formatted_address") else "Current Location"
            } else "Current Location"
        } catch (e: Exception) { "Current Location" }
    }

    private fun parseDurationToSeconds(duration: String): Int {
        return try {
            if (duration.contains("hr")) {
                val parts = duration.split(" "); val h = parts[0].toIntOrNull() ?: 0; val m = parts.getOrNull(2)?.replace("min", "")?.toIntOrNull() ?: 0
                h * 3600 + m * 60
            } else duration.replace("min", "").trim().toIntOrNull()?.times(60) ?: 0
        } catch (e: Exception) { 0 }
    }

    private fun logRideToHistory(destination: String) {
        val userId = auth.currentUser?.uid ?: return
        firestore.collection("users").document(userId).collection("history")
            .add(hashMapOf("message" to "Started a ride to $destination", "timestamp" to FieldValue.serverTimestamp()))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Location Updates", NotificationManager.IMPORTANCE_DEFAULT)
            (requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun sendNotification(title: String, message: String) {
        val builder = NotificationCompat.Builder(requireContext(), CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_blaze_rider).setContentTitle(title).setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT).setAutoCancel(true)
        (requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(System.currentTimeMillis().toInt(), builder.build())
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap; map.uiSettings.isMyLocationButtonEnabled = false
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) enableMyLocation()
        else requestLocationPermissions()
    }

    override fun onMapClick(point: LatLng) {}

    private fun requestLocationPermissions() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) enableMyLocation()
        else permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun fetchWeatherAndSendNotification(lat: Double, lng: Double) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = "https://api.openweathermap.org/data/2.5/weather?lat=$lat&lon=$lng&appid=$weatherApiKey&units=metric"
                val response = okHttpClient.newCall(Request.Builder().url(url).build()).execute()
                val json = response.body?.string() ?: return@launch
                val jsonObject = JSONObject(json)
                val weather = jsonObject.getJSONArray("weather").getJSONObject(0)
                val desc = weather.getString("description").replaceFirstChar { it.uppercase() }
                val temp = jsonObject.getJSONObject("main").getDouble("temp")
                currentCityName = jsonObject.getString("name").ifEmpty { "Unknown" }
                val msg = "Location: $currentCityName ($lat, $lng)\nWeather: $desc, Temp: ${String.format("%.1f", temp)}°C"
                withContext(Dispatchers.Main) { sendNotification("Weather Update", msg) }
                saveNotificationToFirestore(msg, jsonObject, lat, lng)
            } catch (e: Exception) { Log.e("Weather", "Error: ${e.message}") }
        }
    }

    private fun saveNotificationToFirestore(message: String, jsonObject: JSONObject, lat: Double, lng: Double) {
        val userId = auth.currentUser?.uid ?: return
        val weather = jsonObject.getJSONArray("weather").getJSONObject(0)
        val notification = hashMapOf(
            "type" to "weather", "actorId" to null, "entityType" to "weather", "entityId" to null, "message" to message,
            "metadata" to mapOf("weather" to weather.getString("main"), "weatherDescription" to weather.getString("description"),
                "temperature" to jsonObject.getJSONObject("main").getDouble("temp"), "cityName" to jsonObject.getString("name"), "location" to GeoPoint(lat, lng)),
            "isRead" to false, "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp()
        )
        firestore.collection("users").document(userId).collection("notifications").add(notification)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun drawRouteForMotorcycle() {
        if (currentLocation == null || selectedPlaceLat == 0.0) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = "https://maps.googleapis.com/maps/api/directions/json?origin=${currentLocation!!.latitude},${currentLocation!!.longitude}&destination=$selectedPlaceLat,$selectedPlaceLng&mode=driving&key=$apiKey"
                val json = okHttpClient.newCall(Request.Builder().url(url).build()).execute().body?.string() ?: return@launch
                val route = JSONObject(json).getJSONArray("routes").getJSONObject(0)
                val leg = route.getJSONArray("legs").getJSONObject(0)
                val points = PolyUtil.decode(route.getJSONObject("overview_polyline").getString("points"))
                val durationMin = leg.getJSONObject("duration").getInt("value") / 60
                val distKm = leg.getJSONObject("distance").getInt("value") / 1000.0
                
                withContext(Dispatchers.Main) {
                    map.addPolyline(PolylineOptions().addAll(points).color(Color.BLUE).width(10f))
                    tripDurationTextView.text = formatDuration(durationMin); tripDistanceTextView.text = "(${String.format("%.1f km", distKm)})"
                    routeInstructions = leg.getJSONArray("steps").let { steps -> (0 until steps.length()).joinToString("\n") { steps.getJSONObject(it).getString("html_instructions").replace("<[^>]*>".toRegex(), "") } }
                    routeDuration = formatDuration(durationMin); routeDistance = String.format("%.1f km", distKm)
                    routeEta = java.time.LocalTime.now().plusMinutes(durationMin.toLong()).format(java.time.format.DateTimeFormatter.ofPattern("hh:mm a"))
                    routePolyline = points.joinToString("|") { "${it.latitude},${it.longitude}" }
                    bottomView.visibility = View.GONE; navigationBottomView.visibility = View.VISIBLE
                }
            } catch (e: Exception) { Log.e("Route", "Error: ${e.message}") }
        }
    }

    private fun formatDuration(m: Int): String = if (m < 60) "$m min" else "${m / 60} hr ${m % 60} min"

    private fun checkAudioPermission(): Boolean = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun requestAudioPermission() = ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_PERMISSION_CODE)

    private fun startSpeechToText() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US") }
        startActivityForResult(intent, SPEECH_REQUEST_CODE)
    }

    private fun isGPSEnabled(): Boolean = (requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager).isProviderEnabled(LocationManager.GPS_PROVIDER)
    private fun showGPSEnabledDialog() {
        MaterialAlertDialogBuilder(requireContext()).setTitle("GPS Required").setMessage("Enable GPS to access location.").setPositiveButton("Enable") { _, _ -> startActivityForResult(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), GPS_SETTINGS_REQUEST_CODE) }.show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        when (requestCode) {
            SPEECH_REQUEST_CODE -> data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { text -> searchPlaceholder.text = text; startActivityForResult(Intent(requireContext(), SearchActivity::class.java).apply { putExtra("SEARCH_QUERY", text) }, SEARCH_ACTIVITY_REQUEST_CODE) }
            SEARCH_ACTIVITY_REQUEST_CODE -> data?.let {
                selectedPlaceName = it.getStringExtra("SEARCH_QUERY"); selectedPlaceAddress = it.getStringExtra("PLACE_ADDRESS"); selectedPlaceLat = it.getDoubleExtra("PLACE_LAT", 0.0); selectedPlaceLng = it.getDoubleExtra("PLACE_LNG", 0.0)
                searchPlaceholder.text = selectedPlaceName; val loc = LatLng(selectedPlaceLat, selectedPlaceLng)
                currentMarker?.remove(); currentMarker = map.addMarker(MarkerOptions().position(loc).title(selectedPlaceName))
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(loc, 15f)); placeNameTextView.text = selectedPlaceName; placeAddressTextView.text = selectedPlaceAddress; bottomView.visibility = View.VISIBLE
            }
            GPS_SETTINGS_REQUEST_CODE -> if (isGPSEnabled()) enableMyLocation()
        }
    }

    private fun clearMarker() {
        map.clear(); currentMarker = null; bottomView.visibility = View.GONE; navigationBottomView.visibility = View.GONE
        searchPlaceholder.text = "Your location"; selectedPlaceLat = 0.0
    }

    override fun onPause() {
        super.onPause()
        stopLocationUpdates()
        val userId = auth.currentUser?.uid ?: return
        FirebaseDatabase.getInstance().reference.child("status").child(userId).setValue(mapOf("state" to "offline", "lastActive" to System.currentTimeMillis(), "location" to mapOf("latitude" to (lastKnownLocation?.latitude ?: 0.0), "longitude" to (lastKnownLocation?.longitude ?: 0.0))))
    }

    override fun onResume() {
        super.onResume()
        if (isGPSEnabled() && ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) enableMyLocation()
    }

    private fun showWelcomeDialog() {
        val title = "Welcome to Blaze Rider\n"; val subtitle = "Fuel Your Passion, Blaze Your Own Trail."
        val fullText = SpannableString(title + subtitle).apply { setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); setSpan(AbsoluteSizeSpan(24, true), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); setSpan(AbsoluteSizeSpan(14, true), title.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        val layout = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_blaze_rider)?.apply { DrawableCompat.setTint(this, Color.BLACK) }
        layout.addView(ImageView(requireContext()).apply { setImageDrawable(icon); layoutParams = LinearLayout.LayoutParams(500, 500).apply { bottomMargin = 20 } })
        layout.addView(TextView(requireContext()).apply { text = fullText; gravity = Gravity.CENTER })
        MaterialAlertDialogBuilder(requireContext()).setView(layout).setPositiveButton("Next") { _, _ -> }.setCancelable(false).show().getButton(android.app.AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(requireContext(), R.color.red_orange))
    }

    private fun getCurrentLocationAndAnimate() {
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            loc?.let { l -> val latLng = LatLng(l.latitude, l.longitude); currentLocation = latLng; lastKnownLocation = latLng; locationViewModel.updateLocation(latLng); map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 17f), 500, null) }
        }
    }
}
