package com.aorv.blazerider

import android.graphics.Bitmap
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import com.aorv.blazerider.databinding.ActivityPreviewRideBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import com.google.maps.android.PolyUtil
import android.graphics.drawable.BitmapDrawable
import android.util.Log

class PreviewRideActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityPreviewRideBinding
    private lateinit var map: GoogleMap
    private val okHttpClient = OkHttpClient()
    private val apiKey = "AIzaSyBdsslBjsFC919mvY0osI8hAmrPOzFp_LE" // Replace with your Google Maps API key
    private val firestore = FirebaseFirestore.getInstance()
    private val TAG = "PreviewRideActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPreviewRideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up close icon click listener
        binding.closeIcon.setOnClickListener { finish() }

        // Initialize map
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as? SupportMapFragment
        mapFragment?.getMapAsync(this) ?: run {
            finish() // Close activity if map fragment is missing
        }

        // Fetch and set user name
        val userUid = intent.getStringExtra("ride_user_uid")
        if (!userUid.isNullOrEmpty()) {
            firestore.collection("users").document(userUid).get()
                .addOnSuccessListener { document ->
                    val user = document.toObject(User::class.java)
                    val fullName = "${user?.firstName ?: ""} ${user?.lastName ?: ""}".trim()
                    binding.userName.text = if (fullName.isNotEmpty()) fullName else "Unknown User"
                }
                .addOnFailureListener {
                    binding.userName.text = "Unknown User"
                }
        } else {
            binding.userName.text = "Unknown User"
        }

        // Set ride details
        val origin = intent.getStringExtra("ride_origin") ?: "Origin"
        val destination = intent.getStringExtra("ride_destination") ?: "Destination"
        binding.rideDetails.text = "$origin to $destination"
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap

        // Get ride data from intent
        val originLat = intent.getDoubleExtra("ride_origin_lat", 0.0)
        val originLng = intent.getDoubleExtra("ride_origin_lng", 0.0)
        val destLat = intent.getDoubleExtra("ride_destination_lat", 0.0)
        val destLng = intent.getDoubleExtra("ride_destination_lng", 0.0)
        val origin = intent.getStringExtra("ride_origin") ?: "Origin"
        val destination = intent.getStringExtra("ride_destination") ?: "Destination"
        val polylineString = intent.getStringExtra("polyline")
        val rideId = intent.getStringExtra("ride_id") ?: ""

        // Validate coordinates
        if (originLat == 0.0 && originLng == 0.0 || destLat == 0.0 && destLng == 0.0) {
            finish() // Close activity if coordinates are invalid
            return
        }

        val originLatLng = LatLng(originLat, originLng)
        val destLatLng = LatLng(destLat, destLng)

        // Add origin and destination markers
        map.addMarker(MarkerOptions().position(originLatLng).title(origin))
        map.addMarker(MarkerOptions().position(destLatLng).title(destination))

        // Fetch and display joined riders' markers
        fetchAndDisplayJoinedRiders(rideId, originLatLng, destLatLng)

        // Draw route
        if (!polylineString.isNullOrEmpty()) {
            drawRouteFromPolyline(polylineString)
        } else {
            fetchDirections(originLatLng, destLatLng)
        }
    }

    private fun fetchAndDisplayJoinedRiders(rideId: String, originLatLng: LatLng, destLatLng: LatLng) {
        if (rideId.isEmpty()) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val rideDoc = firestore.collection("sharedRoutes").document(rideId).get().await()
                if (!rideDoc.exists()) return@launch

                val joinedRidersMap = rideDoc.get("joinedRiders") as? Map<String, Map<String, Any>> ?: emptyMap()
                val boundsBuilder = LatLngBounds.builder().include(originLatLng).include(destLatLng)
                val joinedUsersList = mutableListOf<JoinedUser>()

                for (userId in joinedRidersMap.keys) {
                    val userDoc = firestore.collection("users").document(userId).get().await()
                    val state = userDoc.getString("state")
                    val location = userDoc.get("location") as? GeoPoint
                    val profileImageUrl = userDoc.getString("profileImageUrl")
                    val firstName = userDoc.getString("firstName") ?: "Unknown"

                    joinedUsersList.add(JoinedUser(userId, firstName))

                    // Skip if user is offline or location is (0,0)
                    if (state != "online" || location == null || (location.latitude == 0.0 && location.longitude == 0.0)) {
                        Log.d(TAG, "Skipping user $userId: state=$state, location=$location")
                        continue
                    }

                    // Load profile image as a circular bitmap
                    val bitmap = profileImageUrl?.let {
                        try {
                            val drawable = Glide.with(this@PreviewRideActivity)
                                .asBitmap()
                                .load(it)
                                .apply(RequestOptions.bitmapTransform(CircleCrop()))
                                .submit(100, 100) // Size of the marker icon
                                .get()
                            drawable
                        } catch (e: Exception) {
                            Log.e(TAG, "Error loading profile image for $userId: ${e.message}")
                            null
                        }
                    }

                    withContext(Dispatchers.Main) {
                        val userLatLng = LatLng(location.latitude, location.longitude)
                        map.addMarker(
                            MarkerOptions()
                                .position(userLatLng)
                                .title(firstName)
                                .icon(bitmap?.let { BitmapDescriptorFactory.fromBitmap(it) }
                                    ?: BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
                        )
                        boundsBuilder.include(userLatLng)
                    }
                }

                withContext(Dispatchers.Main) {
                    val bottomSheetFragment = JoinedUsersBottomSheetFragment.newInstance(joinedUsersList)
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.bottom_sheet_container, bottomSheetFragment)
                        .commit()
                }

                // Zoom to fit all points (origin, destination, and riders)
                withContext(Dispatchers.Main) {
                    try {
                        val bounds = boundsBuilder.build()
                        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))
                    } catch (e: Exception) {
                        Log.e(TAG, "Error setting map bounds: ${e.message}")
                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(originLatLng, 14f))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching joined riders: ${e.message}")
            }
        }
    }

    private fun drawRouteFromPolyline(polylineString: String) {
        try {
            // Check if polylineString is an encoded polyline (Google Directions API format)
            if (polylineString.contains("\\")) {
                // Decode Google Maps encoded polyline
                val decodedPoints = PolyUtil.decode(polylineString)
                if (decodedPoints.isNotEmpty()) {
                    map.addPolyline(
                        PolylineOptions()
                            .addAll(decodedPoints)
                            .color(android.graphics.Color.BLUE)
                            .width(5f)
                            .geodesic(true)
                    )
                    return
                }
            }

            // Otherwise, assume custom format: lat1,lng1|lat2,lng2|...
            val points = polylineString.split("|").mapNotNull { point ->
                val coords = point.split(",")
                if (coords.size == 2) {
                    val lat = coords[0].toDoubleOrNull()
                    val lng = coords[1].toDoubleOrNull()
                    if (lat != null && lng != null) LatLng(lat, lng) else null
                } else null
            }

            if (points.isNotEmpty()) {
                map.addPolyline(
                    PolylineOptions()
                        .addAll(points)
                        .color(android.graphics.Color.BLUE)
                        .width(5f)
                        .geodesic(true)
                )
            } else {
                drawFallbackPolyline()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            drawFallbackPolyline()
        }
    }

    private fun fetchDirections(origin: LatLng, destination: LatLng) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = "https://maps.googleapis.com/maps/api/directions/json?" +
                        "origin=${origin.latitude},${origin.longitude}&" +
                        "destination=${destination.latitude},${destination.longitude}&" +
                        "mode=driving&key=$apiKey"

                val request = Request.Builder().url(url).build()
                val response = okHttpClient.newCall(request).execute()
                val json = response.body?.string()

                if (json != null && response.isSuccessful) {
                    val jsonObject = JSONObject(json)
                    val routes = jsonObject.getJSONArray("routes")
                    if (routes.length() == 0) {
                        withContext(Dispatchers.Main) {
                            drawFallbackPolyline()
                        }
                        return@launch
                    }

                    val route = routes.getJSONObject(0)
                    val overviewPolyline = route.getJSONObject("overview_polyline")
                    val encodedPolyline = overviewPolyline.getString("points")
                    val decodedPoints = PolyUtil.decode(encodedPolyline)

                    withContext(Dispatchers.Main) {
                        map.addPolyline(
                            PolylineOptions()
                                .addAll(decodedPoints)
                                .color(android.graphics.Color.BLUE)
                                .width(5f)
                                .geodesic(true)
                        )
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        drawFallbackPolyline()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    drawFallbackPolyline()
                }
            }
        }
    }

    private fun drawFallbackPolyline() {
        val originLat = intent.getDoubleExtra("ride_origin_lat", 0.0)
        val originLng = intent.getDoubleExtra("ride_origin_lng", 0.0)
        val destLat = intent.getDoubleExtra("ride_destination_lat", 0.0)
        val destLng = intent.getDoubleExtra("ride_destination_lng", 0.0)

        if (originLat == 0.0 && originLng == 0.0 || destLat == 0.0 && destLng == 0.0) {
            return // Avoid drawing invalid polyline
        }

        val originLatLng = LatLng(originLat, originLng)
        val destLatLng = LatLng(destLat, destLng)

        map.addPolyline(
            PolylineOptions()
                .add(originLatLng, destLatLng)
                .color(android.graphics.Color.BLUE)
                .width(5f)
        )
    }
}