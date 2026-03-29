package com.aorv.blazerider

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.Drawable
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.aorv.blazerider.databinding.ActivityInAppNavigationBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.maps.android.PolyUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class InAppNavigationActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityInAppNavigationBinding
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val TAG = "InAppNavActivity"

    private var currentRide: SharedRide? = null
    private var hasArrived = false
    
    private var googleMap: GoogleMap? = null
    private var routePolyline: Polyline? = null
    private var destinationMarker: Marker? = null
    private val okHttpClient = OkHttpClient()
    private val apiKey = "AIzaSyBdsslBjsFC919mvY0osI8hAmrPOzFp_LE"

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var currentLocation: LatLng? = null
    private var currentBearing: Float = 0f
    
    private var destName: String? = null
    private var destLat: Double = 0.0
    private var destLng: Double = 0.0
    private var startLocationName: String? = null
    private var isFirstLocation = true

    private var liveLocationsListener: ListenerRegistration? = null
    private val riderMarkers = mutableMapOf<String, Marker>()
    private val loadingMarkers = mutableSetOf<String>()
    private val riderProfileImages = mutableMapOf<String, String>() // userId -> profileImageUrl
    private var currentUserProfileUrl: String? = null
    private var isAdmin = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false) // Enable drawing behind system bars
        binding = ActivityInAppNavigationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply window insets to prevent UI overlap with status bar and navigation bar
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            
            // Top panel (Instruction Card) padding to avoid status bar
            binding.instructionPanel.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = systemBars.top + (12 * resources.displayMetrics.density).toInt()
            }
            
            // Bottom panel (Nav Info Card) margin to avoid navigation bar
            binding.navInfoCard.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                val margin12dp = (12 * resources.displayMetrics.density).toInt()
                bottomMargin = systemBars.bottom + margin12dp
                leftMargin = margin12dp
                rightMargin = margin12dp
            }
            
            insets
        }

        @Suppress("DEPRECATION")
        currentRide = intent.getParcelableExtra("EXTRA_RIDE")
        
        if (currentRide == null) {
            destName = intent.getStringExtra("destination_name")
            destLat = intent.getDoubleExtra("destination_lat", 0.0)
            destLng = intent.getDoubleExtra("destination_lng", 0.0)
            startLocationName = intent.getStringExtra("start_location_name") ?: "Current Location"
        } else {
            destName = currentRide?.destination
            destLat = currentRide?.destinationCoordinates?.get("latitude") ?: 0.0
            destLng = currentRide?.destinationCoordinates?.get("longitude") ?: 0.0
            startLocationName = currentRide?.origin ?: "Current Location"
        }

        if (destLat == 0.0 && destLng == 0.0) {
            Toast.makeText(this, "Destination data missing", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        binding.exitNavButton.setOnClickListener { handleCancel() }
        binding.textDestination.text = destName ?: "Unknown"

        // Fetch current user's profile URL and admin status
        auth.currentUser?.uid?.let { uid ->
            firestore.collection("users").document(uid).get()
                .addOnSuccessListener { doc ->
                    currentUserProfileUrl = doc.getString("profileImageUrl")
                    isAdmin = when (val adminValue = doc.get("admin")) {
                        is Boolean -> adminValue
                        is String -> adminValue.toBoolean()
                        is Long -> adminValue == 1L
                        else -> false
                    }
                    if (uid == "A7USXq3qwFgCH4sov6mmPdtaGOn2") isAdmin = true
                }
        }

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        binding.gpsButton.setOnClickListener {
            currentLocation?.let {
                val cameraPosition = CameraPosition.Builder()
                    .target(it)
                    .zoom(18f)
                    .tilt(45f)
                    .bearing(currentBearing)
                    .build()
                googleMap?.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
            }
        }

        initLocationCallback()
        startListeningToLiveLocations()
    }

    private fun startListeningToLiveLocations() {
        val rideId = currentRide?.sharedRoutesId ?: return
        val currentUserId = auth.currentUser?.uid ?: return

        liveLocationsListener = firestore.collection("sharedRoutes")
            .document(rideId)
            .collection("liveLocations")
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    Log.w(TAG, "Listen failed.", e)
                    return@addSnapshotListener
                }

                snapshots?.documentChanges?.forEach { dc ->
                    val userId = dc.document.id
                    if (userId == currentUserId) return@forEach

                    when (dc.type) {
                        com.google.firebase.firestore.DocumentChange.Type.ADDED,
                        com.google.firebase.firestore.DocumentChange.Type.MODIFIED -> {
                            val lat = dc.document.getDouble("latitude") ?: return@forEach
                            val lng = dc.document.getDouble("longitude") ?: return@forEach
                            val bearing = dc.document.getDouble("bearing")?.toFloat() ?: 0f
                            val pos = LatLng(lat, lng)
                            updateRiderMarker(userId, pos, bearing)
                        }
                        com.google.firebase.firestore.DocumentChange.Type.REMOVED -> {
                            riderMarkers[userId]?.remove()
                            riderMarkers.remove(userId)
                        }
                    }
                }
            }
    }

    private fun updateRiderMarker(userId: String, position: LatLng, bearing: Float) {
        val marker = riderMarkers[userId]
        if (marker != null) {
            marker.position = position
            marker.rotation = bearing
        } else if (!loadingMarkers.contains(userId)) {
            loadingMarkers.add(userId)
            if (riderProfileImages.containsKey(userId)) {
                createMarkerWithImage(userId, position, riderProfileImages[userId], bearing)
            } else {
                firestore.collection("users").document(userId).get()
                    .addOnSuccessListener { doc ->
                        val imageUrl = doc.getString("profileImageUrl")
                        riderProfileImages[userId] = imageUrl ?: ""
                        createMarkerWithImage(userId, position, imageUrl, bearing)
                    }
                    .addOnFailureListener {
                        loadingMarkers.remove(userId)
                    }
            }
        }
    }

    private fun createMarkerWithImage(userId: String, position: LatLng, imageUrl: String?, bearing: Float) {
        if (googleMap == null) {
            loadingMarkers.remove(userId)
            return
        }

        val requestBuilder = Glide.with(this).asBitmap()
        val finalUrl = if (imageUrl.isNullOrEmpty()) R.drawable.ic_anonymous else imageUrl

        requestBuilder.load(finalUrl).circleCrop().into(object : CustomTarget<Bitmap>(120, 120) {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                val markerIcon = BitmapDescriptorFactory.fromBitmap(getCircularBitmapWithArrow(resource))
                val newMarker = googleMap?.addMarker(
                    MarkerOptions()
                        .position(position)
                        .icon(markerIcon)
                        .anchor(0.5f, 0.5f)
                        .rotation(bearing)
                        .flat(true)
                        .zIndex(1.0f)
                )
                if (newMarker != null) {
                    riderMarkers[userId] = newMarker
                }
                loadingMarkers.remove(userId)
            }

            override fun onLoadCleared(placeholder: Drawable?) {
                loadingMarkers.remove(userId)
            }
        })
    }

    private fun getCircularBitmapWithArrow(bitmap: Bitmap): Bitmap {
        val size = 250 // Increased size for prominence
        val centerX = size / 2f
        val centerY = size / 2f
        
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 1. Draw ONE Large prominent direction arrow
        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF4500")
            style = Paint.Style.FILL
        }
        val arrowPath = Path()
        arrowPath.moveTo(centerX, 0f) // Sharp tip at top
        arrowPath.lineTo(centerX - 45, 80f) // Broad base
        arrowPath.lineTo(centerX + 45, 80f) // Broad base
        arrowPath.close()
        canvas.drawPath(arrowPath, arrowPaint)

        // 2. Draw profile circle slightly below the arrow tip for integration
        val radius = 60f
        val borderSize = 10f
        val profileCenterY = centerY + 30f // Positioned to look like the arrow belongs to it

        // White base/border
        paint.color = Color.WHITE
        canvas.drawCircle(centerX, profileCenterY, radius, paint)

        // Profile image
        val innerRadius = radius - borderSize
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, (innerRadius * 2).toInt(), (innerRadius * 2).toInt(), false)
        val shader = BitmapShader(scaledBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { setShader(shader) }
        val matrix = Matrix()
        matrix.setTranslate(centerX - innerRadius, profileCenterY - innerRadius)
        shader.setLocalMatrix(matrix)
        canvas.drawCircle(centerX, profileCenterY, innerRadius, imagePaint)
        
        // Red-Orange border
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF4500")
            style = Paint.Style.STROKE
            strokeWidth = borderSize
        }
        canvas.drawCircle(centerX, profileCenterY, radius - borderSize / 2f, borderPaint)

        return output
    }

    private fun handleCancel() {
        if (!hasArrived) {
            val userId = auth.currentUser?.uid ?: return
            val ride = currentRide
            
            if (ride != null && ride.userUid == userId) {
                // Show confirmation to cancel the whole event if they are the creator
                AlertDialog.Builder(this)
                    .setTitle("Cancel Ride Event")
                    .setMessage("Are you sure you want to cancel this ride event for everyone?")
                    .setPositiveButton("Yes, Cancel") { _, _ ->
                        cancelSharedRide(ride)
                        navigateToAppropriateScreen()
                    }
                    .setNegativeButton("No, Just Exit") { _, _ ->
                        logNavigationToHistory("Exited Early")
                        navigateToAppropriateScreen()
                    }
                    .show()
                return
            } else {
                logNavigationToHistory("Cancelled")
            }
        }
        navigateToAppropriateScreen()
    }

    private fun cancelSharedRide(ride: SharedRide) {
        val userId = auth.currentUser?.uid ?: return
        val rideId = ride.sharedRoutesId ?: return

        val batch = firestore.batch()
        batch.update(firestore.collection("sharedRoutes").document(rideId), "status", "cancelled")
        batch.update(firestore.collection("users").document(userId), "currentJoinedRide", null)

        val cancelledRideHistory = RideHistory(
            datetime = Timestamp.now(),
            destination = ride.destination,
            distance = ride.distance,
            duration = ride.duration,
            origin = ride.origin,
            status = "Cancelled",
            userUid = userId,
            sharedRoutesId = rideId
        )
        batch.set(firestore.collection("users").document(userId).collection("rideHistory").document(), cancelledRideHistory)

        ride.joinedRiders?.keys?.forEach { riderId ->
            batch.update(firestore.collection("users").document(riderId), "currentJoinedRide", null)
            val notifRef = firestore.collection("users").document(riderId).collection("notifications").document()
            batch.set(notifRef, mapOf(
                "actorId" to userId,
                "createdAt" to Timestamp.now(),
                "message" to "The ride from ${ride.origin} has been cancelled by the host.",
                "type" to "ride_cancelled",
                "isRead" to false
            ))
        }

        batch.commit().addOnSuccessListener {
            Toast.makeText(this, "Ride event cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun navigateToAppropriateScreen() {
        val intent = if (isAdmin) {
            Intent(this, AdminActivity::class.java).apply {
                putExtra("SELECT_TAB", R.id.nav_events)
            }
        } else {
            Intent(this, SharedRidesActivity::class.java).apply {
                putExtra("SELECT_TAB", 1)
            }
        }
        
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
        finish()
    }

    private fun initLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: return
                val latLng = LatLng(location.latitude, location.longitude)
                currentLocation = latLng
                currentBearing = location.bearing
                updateNavigationUI(latLng, location.bearing)
            }
        }
    }

    private fun updateNavigationUI(latLng: LatLng, bearing: Float) {
        if (hasArrived) return

        val destLatLng = LatLng(destLat, destLng)

        val loc1 = Location("").apply {
            latitude = latLng.latitude
            longitude = latLng.longitude
        }
        val loc2 = Location("").apply {
            latitude = destLat
            longitude = destLng
        }

        val distanceInMeters = loc1.distanceTo(loc2)
        binding.textDistanceRemaining.text = if (distanceInMeters >= 1000) {
            String.format("%.1f km", distanceInMeters / 1000)
        } else {
            String.format("%.0f m", distanceInMeters)
        }

        // Update map camera
        googleMap?.let { map ->
            val cameraPosition = CameraPosition.Builder()
                .target(latLng)
                .zoom(18f)
                .tilt(45f)
                .bearing(bearing)
                .build()
            
            if (isFirstLocation) {
                map.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
                isFirstLocation = false
            } else {
                map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
            }
        }
        
        // Update my own marker
        updateRiderMarker(auth.currentUser?.uid ?: "me", latLng, bearing)
        
        fetchAndDrawRoute(latLng, destLatLng)

        auth.currentUser?.uid?.let { userId ->
            val liveLocationData = hashMapOf(
                "latitude" to latLng.latitude,
                "longitude" to latLng.longitude,
                "bearing" to bearing
            )

            currentRide?.sharedRoutesId?.let { rideId ->
                firestore.collection("sharedRoutes")
                    .document(rideId)
                    .collection("liveLocations")
                    .document(userId)
                    .set(liveLocationData)
            }
        }

        if (distanceInMeters < 50) {
            binding.textStatus.text = "Status: Arrived!"
            binding.textStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            hasArrived = true
            
            if (currentRide != null) {
                completeRide(currentRide!!)
            } else {
                logNavigationToHistory("Completed")
                Toast.makeText(this, "You have arrived!", Toast.LENGTH_SHORT).show()
            }
            stopLocationUpdates()
        }
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
            .setWaitForAccurateLocation(true)
            .setMinUpdateIntervalMillis(2000)
            .build()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    val latLng = LatLng(it.latitude, it.longitude)
                    currentLocation = latLng
                    currentBearing = it.bearing
                    if (googleMap != null) {
                        updateNavigationUI(latLng, it.bearing)
                    }
                }
            }
        }
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    override fun onMapReady(p0: GoogleMap) {
        this.googleMap = p0
        this.googleMap?.uiSettings?.isMyLocationButtonEnabled = false
        this.googleMap?.uiSettings?.isZoomControlsEnabled = false
        
        // Disable default blue dot since we're using profile markers
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            this.googleMap?.isMyLocationEnabled = false 
            startLocationUpdates()
        }

        val destLatLng = LatLng(destLat, destLng)
        destinationMarker = this.googleMap?.addMarker(
            MarkerOptions()
                .position(destLatLng)
                .title(destName)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
        )

        currentLocation?.let {
            val cameraPosition = CameraPosition.Builder()
                .target(it)
                .zoom(18f)
                .tilt(45f)
                .bearing(currentBearing)
                .build()
            this.googleMap?.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
            isFirstLocation = false
            updateNavigationUI(it, currentBearing)
        }
    }

    private fun fetchAndDrawRoute(origin: LatLng, destination: LatLng) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = "https://maps.googleapis.com/maps/api/directions/json?origin=${origin.latitude},${origin.longitude}&destination=${destination.latitude},${destination.longitude}&mode=driving&key=$apiKey"
                val response = okHttpClient.newCall(Request.Builder().url(url).build()).execute()
                val json = response.body?.string() ?: return@launch
                val jsonObject = JSONObject(json)
                
                if (jsonObject.getString("status") == "OK") {
                    val route = jsonObject.getJSONArray("routes").getJSONObject(0)
                    val leg = route.getJSONArray("legs").getJSONObject(0)
                    
                    val durationText = leg.getJSONObject("duration").getString("text")
                    val durationVal = leg.getJSONObject("duration").getInt("value")
                    val steps = leg.getJSONArray("steps")
                    val currentStep = steps.getJSONObject(0)
                    val htmlInstruction = currentStep.getString("html_instructions").replace("<[^>]*>".toRegex(), "")
                    
                    val points = PolyUtil.decode(route.getJSONObject("overview_polyline").getString("points"))
                    
                    val calendar = Calendar.getInstance()
                    calendar.add(Calendar.SECOND, durationVal)
                    val eta = SimpleDateFormat("h:mm a", Locale.getDefault()).format(calendar.time)

                    withContext(Dispatchers.Main) {
                        binding.tripDuration.text = durationText
                        binding.tripEta.text = "ETA: $eta"
                        binding.textInstruction.text = htmlInstruction
                        
                        routePolyline?.remove()
                        routePolyline = googleMap?.addPolyline(
                            PolylineOptions()
                                .addAll(points)
                                .color(Color.parseColor("#FF4500"))
                                .width(14f)
                                .geodesic(true)
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching route", e)
            }
        }
    }

    private fun completeRide(ride: SharedRide) {
        val userId = auth.currentUser?.uid ?: return
        val rideId = ride.sharedRoutesId ?: return
        val isRideCreator = ride.userUid == userId

        val batch = firestore.batch()
        batch.update(firestore.collection("users").document(userId), "currentJoinedRide", null)

        val rideHistory = RideHistory(
            datetime = Timestamp.now(),
            destination = ride.destination,
            distance = ride.distance,
            duration = ride.duration,
            origin = ride.origin,
            status = "Completed",
            userUid = userId,
            sharedRoutesId = rideId
        )
        batch.set(firestore.collection("users").document(userId).collection("rideHistory").document(), rideHistory)

        val rideRef = firestore.collection("sharedRoutes").document(rideId)
        if (isRideCreator) {
            batch.update(rideRef, "status", "completed")
        } else {
            batch.update(rideRef, "joinedRiders.$userId", FieldValue.delete())
        }

        batch.set(firestore.collection("users").document(userId).collection("notifications").document(), mapOf(
            "actorId" to userId,
            "createdAt" to Timestamp.now(),
            "message" to "You have arrived at your destination for the ride to ${ride.destination}.",
            "type" to "ride_arrived",
            "isRead" to false
        ))

        batch.commit().addOnSuccessListener {
            Toast.makeText(this, "Ride completed!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun logNavigationToHistory(status: String) {
        val userId = auth.currentUser?.uid ?: return
        
        val rideHistory = RideHistory(
            datetime = Timestamp.now(),
            destination = destName,
            distance = 0.0,
            duration = 0.0,
            origin = startLocationName,
            status = status,
            userUid = userId,
            sharedRoutesId = null
        )
        
        firestore.collection("users").document(userId).collection("rideHistory").add(rideHistory)
            .addOnSuccessListener {
                Log.d(TAG, "Personal ride history logged: $status")
            }
    }

    override fun onResume() {
        super.onResume()
        if (!hasArrived) startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        stopLocationUpdates()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        liveLocationsListener?.remove()
    }
    
    override fun onBackPressed() {
        handleCancel()
    }
}
