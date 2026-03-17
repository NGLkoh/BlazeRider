package com.aorv.blazerider

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.Places
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

class CreateRideActivity : AppCompatActivity() {

    // UI Elements
    private lateinit var etRideName: TextInputEditText
    private lateinit var etDescription: TextInputEditText
    private lateinit var tvSelectedDateTime: TextView
    private lateinit var tvStartLocation: TextView
    private lateinit var tvEndLocation: TextView
    private lateinit var tvScheduledTime: TextView
    private lateinit var layoutSchedulePost: LinearLayout
    private lateinit var rbPostLater: RadioButton

    // Data Holders
    private var rideCalendar = Calendar.getInstance()
    private var scheduledCalendar = Calendar.getInstance()
    
    // Custom Place class to hold data from SearchActivity
    data class CustomPlace(val name: String, val address: String, val latLng: LatLng)
    private var startPlace: CustomPlace? = null
    private var endPlace: CustomPlace? = null

    // Flags to check if user actually picked a date
    private var isRideDateSet = false
    private var isScheduleDateSet = false

    // Firebase
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // 1. Start Location Launcher using custom SearchActivity
    private val startLocationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val name = data?.getStringExtra("SEARCH_QUERY") ?: ""
            val address = data?.getStringExtra("PLACE_ADDRESS") ?: ""
            val lat = data?.getDoubleExtra("PLACE_LAT", 0.0) ?: 0.0
            val lng = data?.getDoubleExtra("PLACE_LNG", 0.0) ?: 0.0
            
            startPlace = CustomPlace(name, address, LatLng(lat, lng))
            tvStartLocation.text = name
        }
    }

    // 2. End Location Launcher using custom SearchActivity
    private val endLocationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val name = data?.getStringExtra("SEARCH_QUERY") ?: ""
            val address = data?.getStringExtra("PLACE_ADDRESS") ?: ""
            val lat = data?.getDoubleExtra("PLACE_LAT", 0.0) ?: 0.0
            val lng = data?.getDoubleExtra("PLACE_LNG", 0.0) ?: 0.0
            
            endPlace = CustomPlace(name, address, LatLng(lat, lng))
            tvEndLocation.text = name
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_create_ride)

        // Initialize Places
        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, getString(R.string.google_maps_key))
        }

        initViews()
        setupListeners()
    }

    private fun initViews() {
        etRideName = findViewById(R.id.etRideName)
        etDescription = findViewById(R.id.etDescription)
        tvSelectedDateTime = findViewById(R.id.tvSelectedDateTime)
        tvStartLocation = findViewById(R.id.tvStartLocation)
        tvEndLocation = findViewById(R.id.tvEndLocation)
        tvScheduledTime = findViewById(R.id.tvScheduledTime)
        layoutSchedulePost = findViewById(R.id.layoutSchedulePost)
        rbPostLater = findViewById(R.id.rbPostLater)
    }

    private fun setupListeners() {
        // RIDE Date & Time
        findViewById<Button>(R.id.btnRideDate).setOnClickListener { 
            showDatePicker(rideCalendar, setMinDate = true) { updateRideDateTimeDisplay() } 
        }
        findViewById<Button>(R.id.btnRideTime).setOnClickListener { 
            showTimePicker(rideCalendar) { updateRideDateTimeDisplay() } 
        }

        // Locations - Use custom SearchActivity
        findViewById<MaterialCardView>(R.id.cardStartLocation).setOnClickListener { 
            val intent = Intent(this, SearchActivity::class.java).apply {
                putExtra("HINT", "Search Start Location")
            }
            startLocationLauncher.launch(intent)
        }
        findViewById<MaterialCardView>(R.id.cardEndLocation).setOnClickListener { 
            val intent = Intent(this, SearchActivity::class.java).apply {
                putExtra("HINT", "Search Destination")
            }
            endLocationLauncher.launch(intent)
        }

        // Post Option Toggle
        findViewById<RadioGroup>(R.id.radioGroupPost).setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbPostLater) {
                layoutSchedulePost.visibility = View.VISIBLE
            } else {
                layoutSchedulePost.visibility = View.GONE
            }
        }

        // SCHEDULE Date & Time
        findViewById<Button>(R.id.btnScheduleDate).setOnClickListener {
            showDatePicker(scheduledCalendar, setMinDate = true) {
                showTimePicker(scheduledCalendar) {
                    isScheduleDateSet = true
                    val format = SimpleDateFormat("MMM dd, yyyy 'at' hh:mm a", Locale.getDefault())
                    tvScheduledTime.text = format.format(scheduledCalendar.time)
                }
            }
        }

        // CREATE BUTTON
        findViewById<Button>(R.id.btnCreateEvent).setOnClickListener { checkExistingRideAndCreate() }
    }

    private fun showDatePicker(calendar: Calendar, setMinDate: Boolean = false, onSet: () -> Unit) {
        val datePickerDialog = DatePickerDialog(this, { _, year, month, day ->
            calendar.set(year, month, day)
            onSet()
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH))
        
        if (setMinDate) {
            datePickerDialog.datePicker.minDate = System.currentTimeMillis() - 1000
        }
        
        datePickerDialog.show()
    }

    private fun showTimePicker(calendar: Calendar, onSet: () -> Unit) {
        TimePickerDialog(this, { _, hour, minute ->
            calendar.set(Calendar.HOUR_OF_DAY, hour)
            calendar.set(Calendar.MINUTE, minute)
            onSet()
        }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), false).show()
    }

    private fun updateRideDateTimeDisplay() {
        isRideDateSet = true
        val format = SimpleDateFormat("MMM dd, yyyy 'at' hh:mm a", Locale.getDefault())
        tvSelectedDateTime.text = format.format(rideCalendar.time)
    }

    private fun calculateDistance(start: LatLng?, end: LatLng?): Double {
        if (start == null || end == null) return 0.0
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            start.latitude, start.longitude,
            end.latitude, end.longitude,
            results
        )
        return (results[0] / 1000).toDouble()
    }

    private fun estimateDuration(distanceKm: Double): Double {
        val averageSpeedKmh = 35.0
        val hours = distanceKm / averageSpeedKmh
        return hours * 3600
    }

    private fun checkExistingRideAndCreate() {
        val userId = auth.currentUser?.uid ?: return
        db.collection("users").document(userId).get()
            .addOnSuccessListener { document ->
                val currentJoinedRide = document.getString("currentJoinedRide")
                if (!currentJoinedRide.isNullOrEmpty()) {
                    Toast.makeText(this, "You already have an active ride. Finish it first.", Toast.LENGTH_SHORT).show()
                } else {
                    db.collection("rides")
                        .whereEqualTo("hostId", userId)
                        .whereEqualTo("isScheduled", true)
                        .get()
                        .addOnSuccessListener { scheduledSnapshot ->
                            if (!scheduledSnapshot.isEmpty) {
                                Toast.makeText(this, "You have a scheduled ride. Cancel or complete it first.", Toast.LENGTH_SHORT).show()
                            } else {
                                validateAndCreateRide()
                            }
                        }
                        .addOnFailureListener { validateAndCreateRide() }
                }
            }
            .addOnFailureListener { validateAndCreateRide() }
    }

    private fun validateAndCreateRide() {
        val name = etRideName.text.toString().trim()
        val desc = etDescription.text.toString().trim()
        val user = auth.currentUser

        if (name.isEmpty() || desc.isEmpty()) {
            Toast.makeText(this, "Please enter name and description", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isRideDateSet) {
            Toast.makeText(this, "Please select ride date and time", Toast.LENGTH_SHORT).show()
            return
        }
        if (rideCalendar.timeInMillis < System.currentTimeMillis()) {
            Toast.makeText(this, "Ride date and time cannot be in the past", Toast.LENGTH_SHORT).show()
            return
        }
        if (startPlace == null || endPlace == null) {
            Toast.makeText(this, "Please select Start and End locations", Toast.LENGTH_SHORT).show()
            return
        }
        if (user == null) return

        val isScheduled = rbPostLater.isChecked
        if (isScheduled && !isScheduleDateSet) {
            Toast.makeText(this, "Please select a date to auto-post", Toast.LENGTH_SHORT).show()
            return
        }
        if (isScheduled && scheduledCalendar.timeInMillis < System.currentTimeMillis()) {
            Toast.makeText(this, "Scheduled post time cannot be in the past", Toast.LENGTH_SHORT).show()
            return
        }

        val rideDistance = calculateDistance(startPlace?.latLng, endPlace?.latLng)
        val rideDurationSeconds = estimateDuration(rideDistance)

        val batch = db.batch()
        val rideRef = db.collection("rides").document()
        val postRef = db.collection("posts").document()
        val sharedRouteRef = db.collection("sharedRoutes").document()

        val rideData = hashMapOf(
            "rideName" to name,
            "description" to desc,
            "rideTimestamp" to rideCalendar.time,
            "startLocationName" to startPlace?.name,
            "startLocationAddress" to startPlace?.address,
            "startLat" to startPlace?.latLng?.latitude,
            "startLng" to startPlace?.latLng?.longitude,
            "endLocationName" to endPlace?.name,
            "endLocationAddress" to endPlace?.address,
            "endLat" to endPlace?.latLng?.latitude,
            "endLng" to endPlace?.latLng?.longitude,
            "hostId" to user.uid,
            "createdAt" to java.util.Date(),
            "distance" to rideDistance,
            "duration" to rideDurationSeconds
        )

        val postTime = if (isScheduled) scheduledCalendar.time else java.util.Date()

        val sharedRideData = hashMapOf(
            "datetime" to Timestamp(rideCalendar.time),
            "createdAt" to Timestamp(postTime),
            "destination" to endPlace?.name,
            "destinationCoordinates" to mapOf(
                "latitude" to (endPlace?.latLng?.latitude ?: 0.0),
                "longitude" to (endPlace?.latLng?.longitude ?: 0.0)
            ),
            "distance" to rideDistance,
            "duration" to rideDurationSeconds,
            "origin" to startPlace?.name,
            "originCoordinates" to mapOf(
                "latitude" to (startPlace?.latLng?.latitude ?: 0.0),
                "longitude" to (startPlace?.latLng?.longitude ?: 0.0)
            ),
            "userUid" to user.uid,
            "status" to "active",
            "isAdminEvent" to true,
            "isScheduled" to isScheduled
        )

        val postData = hashMapOf(
            "userId" to user.uid,
            "content" to "🏍️ NEW RIDE ALERT: $name\n\n$desc\n\n📍 From: ${startPlace?.name}\n🏁 To: ${endPlace?.name}\n📅 When: ${tvSelectedDateTime.text}\n📏 Distance: ${String.format("%.2f", rideDistance)} km",
            "createdAt" to postTime,
            "admin" to true,
            "isScheduled" to isScheduled,
            "type" to "ride_event",
            "rideId" to rideRef.id,
            "sharedRouteId" to sharedRouteRef.id
        )

        batch.set(rideRef, rideData)
        batch.set(sharedRouteRef, sharedRideData)
        batch.set(postRef, postData)

        batch.commit()
            .addOnSuccessListener {
                if (isScheduled) {
                    scheduleWork(postRef.id, sharedRouteRef.id, scheduledCalendar.timeInMillis)
                }
                Toast.makeText(this, "Admin Ride Event Created!", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Error: ${it.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun scheduleWork(postId: String, sharedRouteId: String, scheduleTimestamp: Long) {
        val delay = scheduleTimestamp - System.currentTimeMillis()
        if (delay <= 0) return

        val data = Data.Builder()
            .putString("postId", postId)
            .putString("sharedRouteId", sharedRouteId)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<ScheduledPostWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(data)
            .build()

        WorkManager.getInstance(this).enqueue(workRequest)
    }
}