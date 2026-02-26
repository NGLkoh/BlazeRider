package com.aorv.blazerider

import android.graphics.*
import android.graphics.drawable.Drawable
import android.location.Address
import android.location.Geocoder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.animation.BounceInterpolator
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import com.aorv.blazerider.databinding.ActivityPreviewRideBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import com.google.maps.android.PolyUtil
import java.util.Locale

class PreviewRideActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityPreviewRideBinding
    private lateinit var map: GoogleMap
    private val okHttpClient = OkHttpClient()
    private val apiKey = "AIzaSyBdsslBjsFC919mvY0osI8hAmrPOzFp_LE"
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val locationViewModel: LocationViewModel by viewModels()

    private var myLocationMarker: Marker? = null
    private var myProfileBitmap: Bitmap? = null
    private var myPreviousLocation: LatLng? = null 
    private val participantMarkers = mutableMapOf<String, Marker>()
    private val participantListeners = mutableMapOf<String, ListenerRegistration>()
    
    private val TAG = "PreviewRideActivity"
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPreviewRideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.closeIcon.setOnClickListener { finish() }

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as? SupportMapFragment
        mapFragment?.getMapAsync(this) ?: run { finish() }

        val userUid = intent.getStringExtra("ride_user_uid")
        fetchAndSetUserName(userUid)

        val origin = intent.getStringExtra("ride_origin") ?: "Origin"
        val destination = intent.getStringExtra("ride_destination") ?: "Destination"
        val originLat = intent.getDoubleExtra("ride_origin_lat", 0.0)
        val originLng = intent.getDoubleExtra("ride_origin_lng", 0.0)

        val displayOrigin = if (origin.equals("Current Location", ignoreCase = true)) {
            getReadableAddress(originLat, originLng) ?: origin
        } else {
            origin
        }

        binding.rideDetails.text = "$displayOrigin to $destination"
    }

    private fun getReadableAddress(lat: Double, lng: Double): String? {
        return try {
            val geocoder = Geocoder(this, Locale.getDefault())
            val addresses: List<Address>? = geocoder.getFromLocation(lat, lng, 1)
            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0]
                val street = address.thoroughfare
                val city = address.locality
                when {
                    street != null && city != null -> "$street, $city"
                    city != null -> city
                    else -> address.subAdminArea ?: address.adminArea
                }
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Reverse geocoding failed: ${e.message}")
            null
        }
    }

    private fun fetchAndSetUserName(userUid: String?) {
        if (userUid.isNullOrEmpty()) {
            binding.userName.text = "Unknown User"
            return
        }
        firestore.collection("users").document(userUid).get()
            .addOnSuccessListener { document ->
                val firstName = document.getString("firstName") ?: ""
                val lastName = document.getString("lastName") ?: ""
                val fullName = "$firstName $lastName".trim()
                binding.userName.text = if (fullName.isNotEmpty()) fullName else "Unknown User"
            }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap

        val originLat = intent.getDoubleExtra("ride_origin_lat", 0.0)
        val originLng = intent.getDoubleExtra("ride_origin_lng", 0.0)
        val destLat = intent.getDoubleExtra("ride_destination_lat", 0.0)
        val destLng = intent.getDoubleExtra("ride_destination_lng", 0.0)
        val origin = intent.getStringExtra("ride_origin") ?: "Origin"
        val destination = intent.getStringExtra("ride_destination") ?: "Destination"
        val polylineString = intent.getStringExtra("polyline")
        val rideId = intent.getStringExtra("ride_id") ?: ""

        val displayOrigin = if (origin.equals("Current Location", ignoreCase = true)) {
            getReadableAddress(originLat, originLng) ?: origin
        } else {
            origin
        }

        if (originLat == 0.0 && originLng == 0.0 || destLat == 0.0 && destLng == 0.0) {
            finish()
            return
        }

        val originLatLng = LatLng(originLat, originLng)
        val destLatLng = LatLng(destLat, destLng)

        map.addMarker(MarkerOptions().position(originLatLng).title("Start: $displayOrigin"))
        map.addMarker(MarkerOptions().position(destLatLng).title("End: $destination"))

        setupMyLocationIndicator()
        fetchAndDisplayJoinedRiders(rideId, originLatLng, destLatLng)

        if (!polylineString.isNullOrEmpty()) {
            drawRouteFromPolyline(polylineString)
        } else {
            fetchDirections(originLatLng, destLatLng)
        }
    }

    private fun setupMyLocationIndicator() {
        val currentUserId = auth.currentUser?.uid ?: return

        firestore.collection("users").document(currentUserId).get()
            .addOnSuccessListener { doc ->
                val profileUrl = doc.getString("profileImageUrl")

                locationViewModel.lastKnownLocation.observe(this) { location ->
                    if (location != null) {
                        val myLatLng = LatLng(location.latitude, location.longitude)

                        CoroutineScope(Dispatchers.Main).launch {
                            if (myProfileBitmap == null) {
                                myProfileBitmap = withContext(Dispatchers.IO) {
                                    try {
                                        Glide.with(this@PreviewRideActivity)
                                            .asBitmap()
                                            .load(profileUrl ?: R.drawable.ic_anonymous)
                                            .apply(RequestOptions.bitmapTransform(CircleCrop()))
                                            .submit(120, 120)
                                            .get()
                                    } catch (e: Exception) { null }
                                }
                            }

                            val bubbleBitmap = createBubbleMarker(myProfileBitmap)

                            if (myLocationMarker == null) {
                                myLocationMarker = map.addMarker(
                                    MarkerOptions()
                                        .position(myLatLng)
                                        .anchor(0.5f, 1f)
                                        .zIndex(10.0f) 
                                        .icon(BitmapDescriptorFactory.fromBitmap(bubbleBitmap))
                                )
                                startBouncingAnimation(myLocationMarker)
                                map.animateCamera(CameraUpdateFactory.newLatLngZoom(myLatLng, 15f))
                            } else {
                                myPreviousLocation?.let { prevLoc ->
                                    if (prevLoc != myLatLng) {
                                        val bearing = MarkerAnimationHelper.getBearing(prevLoc, myLatLng)
                                        myLocationMarker?.rotation = bearing
                                    }
                                }
                                MarkerAnimationHelper.animateMarkerToGB(
                                    myLocationMarker!!, 
                                    myLatLng, 
                                    MarkerAnimationHelper.LatLngInterpolator.Linear()
                                )
                            }
                            myPreviousLocation = myLatLng 
                        }
                    }
                }
            }
    }

    private fun createBubbleMarker(profile: Bitmap?): Bitmap {
        val width = 400
        val height = 200
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.WHITE
        paint.setShadowLayer(10f, 0f, 5f, Color.LTGRAY)
        val rectF = RectF(10f, 10f, width - 10f, height - 50f)
        canvas.drawRoundRect(rectF, 80f, 80f, paint)
        paint.clearShadowLayer()

        val path = Path()
        path.moveTo(width / 2f - 30f, height - 50f)
        path.lineTo(width / 2f, height - 10f)
        path.lineTo(width / 2f + 30f, height - 50f)
        path.close()
        canvas.drawPath(path, paint)

        profile?.let {
            val destRect = Rect(30, 30, 150, 150)
            canvas.drawBitmap(it, null, destRect, null)
        }

        paint.color = Color.parseColor("#FF4500") 
        paint.textSize = 40f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("You are here", 170f, 105f, paint)

        return bitmap
    }

    private fun startBouncingAnimation(marker: Marker?) {
        val start = System.currentTimeMillis()
        val duration: Long = 1500
        val interpolator = BounceInterpolator()

        handler.post(object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - start
                val t = Math.max(0f, 1f - interpolator.getInterpolation(elapsed.toFloat() / duration))
                marker?.setAnchor(0.5f, 1.0f + 0.3f * t)

                if (elapsed < duration) {
                    handler.postDelayed(this, 16)
                } else {
                    handler.postDelayed({ startBouncingAnimation(marker) }, 2000)
                }
            }
        })
    }

    private fun fetchAndDisplayJoinedRiders(rideId: String, originLatLng: LatLng, destLatLng: LatLng) {
        if (rideId.isEmpty()) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val rideDoc = firestore.collection("sharedRoutes").document(rideId).get().await()
                if (!rideDoc.exists()) return@launch

                val hostUid = rideDoc.getString("userUid") ?: ""
                val joinedRidersMap = rideDoc.get("joinedRiders") as? Map<String, Any> ?: emptyMap()
                
                val allParticipants = mutableSetOf<String>()
                allParticipants.add(hostUid)
                allParticipants.addAll(joinedRidersMap.keys)
                
                val currentUserId = auth.currentUser?.uid ?: ""
                val othersToTrack = allParticipants.filter { it != currentUserId }

                val joinedUsersList = mutableListOf<JoinedUser>()

                withContext(Dispatchers.Main) {
                    othersToTrack.forEach { userId ->
                        startTrackingParticipant(userId)
                    }
                    
                    allParticipants.forEach { userId ->
                         firestore.collection("users").document(userId).get().addOnSuccessListener { userDoc ->
                             val firstName = userDoc.getString("firstName") ?: ""
                             val lastName = userDoc.getString("lastName") ?: ""
                             val fullName = "$firstName $lastName".trim()
                             val profileImageUrl = userDoc.getString("profileImageUrl")
                             joinedUsersList.add(JoinedUser(userId, fullName.ifEmpty { "User" }, profileImageUrl))
                             
                             if (joinedUsersList.size == allParticipants.size) {
                                 val bottomSheetFragment = JoinedUsersBottomSheetFragment.newInstance(joinedUsersList)
                                 supportFragmentManager.beginTransaction()
                                     .replace(R.id.bottom_sheet_container, bottomSheetFragment)
                                     .commitAllowingStateLoss()
                             }
                         }
                    }
                }

                withContext(Dispatchers.Main) {
                    val boundsBuilder = LatLngBounds.builder().include(originLatLng).include(destLatLng)
                    try {
                        map.moveCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 150))
                    } catch (e: Exception) {
                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(originLatLng, 12f))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching joined riders: ${e.message}")
            }
        }
    }

    private fun startTrackingParticipant(userId: String) {
        if (participantListeners.containsKey(userId)) return

        val listener = firestore.collection("users").document(userId)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener

                val location = snapshot.get("location") as? Map<String, Double>
                val lat = location?.get("latitude")
                val lng = location?.get("longitude")
                val profileImageUrl = snapshot.getString("profileImageUrl")
                val firstName = snapshot.getString("firstName") ?: "User"

                if (lat != null && lng != null && (lat != 0.0 || lng != 0.0)) {
                    val participantLatLng = LatLng(lat, lng)
                    updateParticipantMarker(userId, participantLatLng, profileImageUrl, firstName)
                }
            }
        participantListeners[userId] = listener
    }

    private fun updateParticipantMarker(userId: String, position: LatLng, imageUrl: String?, name: String) {
        val existingMarker = participantMarkers[userId]

        if (existingMarker == null) {
            val finalUrl = if (imageUrl.isNullOrEmpty()) R.drawable.ic_anonymous else imageUrl
            Glide.with(this).asBitmap().load(finalUrl).circleCrop().into(object : CustomTarget<Bitmap>(100, 100) {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    val markerIcon = BitmapDescriptorFactory.fromBitmap(getCircularBitmapWithBorder(resource))
                    val marker = map.addMarker(
                        MarkerOptions()
                            .position(position)
                            .icon(markerIcon)
                            .anchor(0.5f, 0.5f)
                            .title(name)
                    )
                    if (marker != null) {
                        participantMarkers[userId] = marker
                    }
                }
                override fun onLoadCleared(placeholder: Drawable?) {}
            })
        } else {
            val previousParticipantPosition = existingMarker.position
            if (previousParticipantPosition != position) {
                val bearing = MarkerAnimationHelper.getBearing(previousParticipantPosition, position)
                existingMarker.rotation = bearing
            }
            MarkerAnimationHelper.animateMarkerToGB(existingMarker, position, MarkerAnimationHelper.LatLngInterpolator.Linear())
        }
    }

    private fun getCircularBitmapWithBorder(bitmap: Bitmap): Bitmap {
        val size = 120
        val borderSize = 10
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint().apply { isAntiAlias = true }
        
        paint.color = Color.WHITE
        canvas.drawCircle((size / 2).toFloat(), (size / 2).toFloat(), (size / 2).toFloat(), paint)

        val innerCircleRadius = (size / 2 - borderSize).toFloat()
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, size - borderSize * 2, size - borderSize * 2, false)
        val shader = BitmapShader(scaledBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val imagePaint = Paint().apply { isAntiAlias = true; setShader(shader) }
        val matrix = Matrix()
        matrix.setTranslate(borderSize.toFloat(), borderSize.toFloat())
        shader.setLocalMatrix(matrix)
        canvas.drawCircle((size / 2).toFloat(), (size / 2).toFloat(), innerCircleRadius, imagePaint)
        
        val borderPaint = Paint().apply {
            color = Color.parseColor("#FF4500") 
            style = Paint.Style.STROKE
            strokeWidth = borderSize.toFloat()
            isAntiAlias = true
        }
        canvas.drawCircle((size / 2).toFloat(), (size / 2).toFloat(), (size / 2 - borderSize / 2).toFloat(), borderPaint)
        return output
    }

    private fun drawRouteFromPolyline(polylineString: String) {
        try {
            if (polylineString.contains("\\")) {
                val decodedPoints = PolyUtil.decode(polylineString)
                if (decodedPoints.isNotEmpty()) {
                    map.addPolyline(PolylineOptions().addAll(decodedPoints).color(Color.parseColor("#FF4500")).width(12f))
                    return
                }
            }
            val points = polylineString.split("|").mapNotNull { point ->
                val coords = point.split(",")
                if (coords.size == 2) {
                    val lat = coords[0].toDoubleOrNull()
                    val lng = coords[1].toDoubleOrNull()
                    if (lat != null && lng != null) LatLng(lat, lng) else null
                } else null
            }
            if (points.isNotEmpty()) {
                map.addPolyline(PolylineOptions().addAll(points).color(Color.parseColor("#FF4500")).width(12f))
            } else {
                drawFallbackPolyline()
            }
        } catch (e: Exception) {
            drawFallbackPolyline()
        }
    }

    private fun fetchDirections(origin: LatLng, destination: LatLng) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = "https://maps.googleapis.com/maps/api/directions/json?origin=${origin.latitude},${origin.longitude}&destination=${destination.latitude},${destination.longitude}&mode=driving&key=$apiKey"
                val response = okHttpClient.newCall(Request.Builder().url(url).build()).execute()
                val json = response.body?.string()
                if (json != null && response.isSuccessful) {
                    val routes = JSONObject(json).getJSONArray("routes")
                    if (routes.length() > 0) {
                        val points = PolyUtil.decode(routes.getJSONObject(0).getJSONObject("overview_polyline").getString("points"))
                        withContext(Dispatchers.Main) {
                            map.addPolyline(PolylineOptions().addAll(points).color(Color.parseColor("#FF4500")).width(12f))
                        }
                    } else withContext(Dispatchers.Main) { drawFallbackPolyline() }
                }
            } catch (e: Exception) { withContext(Dispatchers.Main) { drawFallbackPolyline() } }
        }
    }

    private fun drawFallbackPolyline() {
        val originLat = intent.getDoubleExtra("ride_origin_lat", 0.0)
        val originLng = intent.getDoubleExtra("ride_origin_lng", 0.0)
        val destLat = intent.getDoubleExtra("ride_destination_lat", 0.0)
        val destLng = intent.getDoubleExtra("ride_destination_lng", 0.0)
        if (originLat != 0.0 && destLat != 0.0) {
            map.addPolyline(PolylineOptions().add(LatLng(originLat, originLng), LatLng(destLat, destLng)).color(Color.parseColor("#FF4500")).width(8f))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        participantListeners.values.forEach { it.remove() }
        handler.removeCallbacksAndMessages(null)
    }
}