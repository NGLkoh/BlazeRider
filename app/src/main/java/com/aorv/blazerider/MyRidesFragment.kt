package com.aorv.blazerider

import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aorv.blazerider.databinding.ItemRidesBinding
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.toObject
import java.text.SimpleDateFormat
import java.util.*

class MyRidesFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var noRidesText: TextView
    private lateinit var adapter: MyRidesAdapter
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val TAG = "MyRidesFragment"
    private val locationViewModel: LocationViewModel by activityViewModels()

    private var sharedRoutesList: List<SharedRide> = emptyList()
    private var scheduledRidesList: List<SharedRide> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_my_rides, container, false)

        // Initialize views
        recyclerView = view.findViewById(R.id.my_rides_recycler_view)
        noRidesText = view.findViewById(R.id.no_rides_text)

        recyclerView.layoutManager = LinearLayoutManager(context)
        adapter = MyRidesAdapter()
        recyclerView.adapter = adapter

        fetchMyRides()
        fetchScheduledRides()
        return view
    }

    private fun fetchMyRides() {
        val userId = auth.currentUser?.uid ?: return

        firestore.collection("sharedRoutes")
            .orderBy("datetime", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error fetching rides: ${error.message}")
                    return@addSnapshotListener
                }

                val allRides = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        doc.toObject<SharedRide>()?.copy(sharedRoutesId = doc.id)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing ride ${doc.id}: ${e.message}")
                        null
                    }
                } ?: emptyList()

                // Filter for active rides where user is creator or joiner
                sharedRoutesList = allRides.filter { ride ->
                    (ride.userUid == userId || ride.joinedRiders?.containsKey(userId) == true) &&
                            ride.status != "completed" && ride.status != "cancelled"
                }

                updateList()
            }
    }

    private fun fetchScheduledRides() {
        val userId = auth.currentUser?.uid ?: return

        firestore.collection("rides")
            .whereEqualTo("hostId", userId)
            .whereEqualTo("isScheduled", true)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error fetching scheduled rides: ${error.message}")
                    return@addSnapshotListener
                }

                scheduledRidesList = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        val rideTimestampRaw = doc.get("rideTimestamp")
                        val rideTimestamp = when (rideTimestampRaw) {
                            is Long -> Timestamp(Date(rideTimestampRaw))
                            is Timestamp -> rideTimestampRaw
                            else -> Timestamp.now()
                        }

                        SharedRide(
                            datetime = rideTimestamp,
                            destination = doc.getString("endLocationName"),
                            origin = doc.getString("startLocationName"),
                            userUid = doc.getString("hostId"),
                            sharedRoutesId = doc.id,
                            isScheduled = true,
                            status = "scheduled",
                            distance = doc.getDouble("distance"),
                            duration = doc.getDouble("duration"),
                            destinationCoordinates = mapOf(
                                "latitude" to (doc.getDouble("endLat") ?: 0.0),
                                "longitude" to (doc.getDouble("endLng") ?: 0.0)
                            ),
                            originCoordinates = mapOf(
                                "latitude" to (doc.getDouble("startLat") ?: 0.0),
                                "longitude" to (doc.getDouble("startLng") ?: 0.0)
                            )
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing scheduled ride ${doc.id}: ${e.message}")
                        null
                    }
                } ?: emptyList()

                updateList()
            }
    }

    private fun updateList() {
        val combinedRides = (sharedRoutesList + scheduledRidesList).sortedByDescending { it.datetime }

        // Toggle Empty State Visibility
        if (combinedRides.isEmpty()) {
            recyclerView.visibility = View.GONE
            noRidesText.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            noRidesText.visibility = View.GONE
        }

        adapter.submitList(combinedRides)
    }

    inner class MyRidesAdapter : RecyclerView.Adapter<MyRidesAdapter.ViewHolder>() {

        private var rides: List<SharedRide> = emptyList()

        fun submitList(newRides: List<SharedRide>) {
            rides = newRides
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemRidesBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(rides[position])
        }

        override fun getItemCount(): Int = rides.size

        inner class ViewHolder(private val binding: ItemRidesBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(ride: SharedRide) {
                val userId = auth.currentUser?.uid ?: return

                ride.userUid?.let { uid ->
                    firestore.collection("users").document(uid).get()
                        .addOnSuccessListener { userDoc ->
                            val user = userDoc.toObject<User>()
                            binding.riderName.text = "${user?.firstName} ${user?.lastName}"
                            Glide.with(binding.profilePicture.context)
                                .load(user?.profileImageUrl ?: R.drawable.ic_anonymous)
                                .into(binding.profilePicture)
                        }
                }

                binding.dateCreated.text = ride.datetime?.toDate()?.let {
                    SimpleDateFormat("d MMMM yyyy 'at' HH:mm:ss", Locale.getDefault()).format(it)
                } ?: "Unknown"

                val rawOrigin = ride.origin ?: "Unknown"
                if (rawOrigin.equals("Current Location", ignoreCase = true)) {
                    val lat = ride.originCoordinates?.get("latitude") as? Double
                    val lng = ride.originCoordinates?.get("longitude") as? Double
                    val cleanAddress = getAddressFromCoords(lat, lng)
                    binding.origin.text = "Origin: ${cleanAddress ?: rawOrigin}"
                } else {
                    binding.origin.text = "Origin: $rawOrigin"
                }

                binding.destination.text = "Destination: ${ride.destination}"
                binding.distance.text = "Distance: ${ride.distance?.let { String.format("%.2f km", it) } ?: "Unknown"}"
                binding.duration.text = "Duration: ${formatDuration(ride.duration)}"

                val ridersCount = ride.joinedRiders?.size ?: 0
                binding.rideNumbers.text = if (ride.isScheduled && ride.status == "scheduled") {
                    val timeString = ride.datetime?.toDate()?.let {
                        SimpleDateFormat("HH:mm", Locale.getDefault()).format(it)
                    } ?: ""
                    "Scheduled for $timeString"
                } else {
                    "$ridersCount ${if (ridersCount <= 1) "rider" else "riders"} joined"
                }

                binding.joinRideBtn.visibility = View.GONE
                binding.previewRideBtn.visibility = View.GONE

                val isRideCreator = ride.userUid == userId
                
                // Show view riders button for creator if there are joined riders
                if (isRideCreator && ridersCount > 0) {
                    binding.viewRidersBtn.visibility = View.VISIBLE
                    binding.viewRidersBtn.setOnClickListener {
                        showJoinedRidersDialog(ride)
                    }
                } else {
                    binding.viewRidersBtn.visibility = View.GONE
                }

                if (ride.isScheduled && ride.status == "scheduled") {
                    binding.startRouteBtn.visibility = View.GONE
                    binding.arrivedBtn.visibility = View.GONE
                    binding.leaveRideBtn.visibility = View.GONE
                    binding.cancelRideBtn.visibility = View.VISIBLE
                    binding.liveText.visibility = View.GONE
                    binding.liveIndicator.visibility = View.GONE
                } else {
                    if (isRideCreator) {
                        binding.startRouteBtn.visibility = View.VISIBLE
                        binding.arrivedBtn.visibility = View.VISIBLE
                        binding.leaveRideBtn.visibility = View.GONE
                        binding.cancelRideBtn.visibility = View.VISIBLE
                    } else {
                        binding.startRouteBtn.visibility = View.VISIBLE
                        binding.arrivedBtn.visibility = View.VISIBLE
                        binding.leaveRideBtn.visibility = View.VISIBLE
                        binding.cancelRideBtn.visibility = View.GONE
                    }
                }

                // Auto-check for arrival
                locationViewModel.lastKnownLocation.observe(viewLifecycleOwner) { currentLatLng ->
                    if (currentLatLng != null && ride.status != "scheduled") {
                        val destLat = ride.destinationCoordinates?.get("latitude") ?: 0.0
                        val destLng = ride.destinationCoordinates?.get("longitude") ?: 0.0

                        val results = FloatArray(1)
                        Location.distanceBetween(
                            currentLatLng.latitude, currentLatLng.longitude,
                            destLat, destLng, results
                        )

                        val distanceInMeters = results[0]
                        if (distanceInMeters < 100) {
                            binding.arrivedBtn.isEnabled = true
                            binding.arrivedBtn.alpha = 1.0f
                        } else {
                            binding.arrivedBtn.isEnabled = false
                            binding.arrivedBtn.alpha = 0.5f
                        }
                    }
                }

                binding.startRouteBtn.setOnClickListener {
                    val destLat = ride.destinationCoordinates?.get("latitude")
                    val destLng = ride.destinationCoordinates?.get("longitude")

                    if (destLat != null && destLng != null) {
                        val gmmIntentUri = Uri.parse("google.navigation:q=$destLat,$destLng")
                        val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                        mapIntent.setPackage("com.google.android.apps.maps")

                        if (context?.packageManager?.let { mapIntent.resolveActivity(it) } != null) {
                            startActivity(mapIntent)
                        } else {
                            Toast.makeText(requireContext(), "Google Maps is not installed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                binding.arrivedBtn.setOnClickListener {
                    showArrivalConfirmationDialog(ride, isRideCreator)
                }

                binding.leaveRideBtn.setOnClickListener {
                    showLeaveConfirmationDialog(ride)
                }

                binding.cancelRideBtn.setOnClickListener {
                    showCancelConfirmationDialog(ride)
                }
            }

            private fun showJoinedRidersDialog(ride: SharedRide) {
                val ridersList = ride.joinedRiders?.keys?.toList() ?: emptyList()
                if (ridersList.isEmpty()) return

                val riderNames = mutableListOf<String>()
                var loadedCount = 0

                ridersList.forEach { riderId ->
                    firestore.collection("users").document(riderId).get()
                        .addOnSuccessListener { doc ->
                            val firstName = doc.getString("firstName") ?: ""
                            val lastName = doc.getString("lastName") ?: ""
                            riderNames.add("$firstName $lastName")
                            loadedCount++

                            if (loadedCount == ridersList.size) {
                                MaterialAlertDialogBuilder(requireContext())
                                    .setTitle("Joined Riders")
                                    .setItems(riderNames.toTypedArray(), null)
                                    .setPositiveButton("Close", null)
                                    .show()
                            }
                        }
                }
            }

            private fun getAddressFromCoords(lat: Double?, lng: Double?): String? {
                if (lat == null || lng == null) return null
                return try {
                    val geocoder = Geocoder(requireContext(), Locale.getDefault())
                    val addresses: List<Address>? = geocoder.getFromLocation(lat, lng, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val address = addresses[0]
                        val street = address.thoroughfare
                        val city = address.locality
                        val subLocality = address.subLocality

                        when {
                            street != null && city != null -> "$street, $city"
                            street != null -> street
                            subLocality != null && city != null -> "$subLocality, $city"
                            else -> city ?: address.subAdminArea ?: "Unknown Location"
                        }
                    } else null
                } catch (e: Exception) {
                    Log.e(TAG, "Geocoding failed: ${e.message}")
                    null
                }
            }

            private fun showArrivalConfirmationDialog(ride: SharedRide, isRideCreator: Boolean) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Complete Ride")
                    .setMessage("Are you sure you have arrived and want to complete this ride?")
                    .setPositiveButton("Arrived") { _, _ ->
                        completeRide(ride, isRideCreator)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

            private fun showLeaveConfirmationDialog(ride: SharedRide) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Leave Ride")
                    .setMessage("Are you sure you want to leave this ride?")
                    .setPositiveButton("Leave") { _, _ ->
                        leaveRide(ride)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

            private fun showCancelConfirmationDialog(ride: SharedRide) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Cancel Ride")
                    .setMessage("Are you sure you want to cancel this ride? This will notify all joined riders.")
                    .setPositiveButton("Cancel Ride") { _, _ ->
                        cancelRide(ride)
                    }
                    .setNegativeButton("Keep Ride", null)
                    .show()
            }

            private fun completeRide(ride: SharedRide, isRideCreator: Boolean) {
                val userId = auth.currentUser?.uid ?: return
                val rideId = ride.sharedRoutesId ?: return

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

                val batch = firestore.batch()
                val userRef = firestore.collection("users").document(userId)
                batch.update(userRef, "currentJoinedRide", null)

                val historyRef = firestore.collection("users").document(userId).collection("rideHistory").document()
                batch.set(historyRef, rideHistory)

                if (isRideCreator) {
                    val rideRef = firestore.collection("sharedRoutes").document(rideId)
                    batch.update(rideRef, "status", "completed")
                }

                batch.commit().addOnSuccessListener {
                    if (isAdded) {
                        Toast.makeText(requireContext(), "Ride completed!", Toast.LENGTH_SHORT).show()
                    }
                    if (!isRideCreator && ride.joinedRiders?.containsKey(userId) == true) {
                        firestore.collection("sharedRoutes").document(rideId)
                            .update("joinedRiders.$userId", com.google.firebase.firestore.FieldValue.delete())
                    }
                }.addOnFailureListener { e ->
                    Log.e(TAG, "Error: ${e.message}")
                }
            }

            private fun leaveRide(ride: SharedRide) {
                val userId = auth.currentUser?.uid ?: return
                val rideId = ride.sharedRoutesId ?: return

                val batch = firestore.batch()
                batch.update(firestore.collection("users").document(userId), "currentJoinedRide", null)
                batch.update(firestore.collection("sharedRoutes").document(rideId), "joinedRiders.$userId", com.google.firebase.firestore.FieldValue.delete())

                batch.commit().addOnSuccessListener {
                    if (isAdded) Toast.makeText(requireContext(), "You left the ride", Toast.LENGTH_SHORT).show()
                }
            }

            private fun cancelRide(ride: SharedRide) {
                val userId = auth.currentUser?.uid ?: return
                val rideId = ride.sharedRoutesId ?: return

                if (ride.status == "scheduled") {
                    firestore.collection("rides").document(rideId)
                        .update("isScheduled", false)
                        .addOnSuccessListener {
                            if (isAdded) Toast.makeText(requireContext(), "Scheduled ride cancelled", Toast.LENGTH_SHORT).show()
                        }
                    return
                }

                firestore.runTransaction { transaction ->
                    val rideRef = firestore.collection("sharedRoutes").document(rideId)
                    transaction.update(rideRef, "status", "cancelled")
                    transaction.update(firestore.collection("users").document(userId), "currentJoinedRide", null)

                    // Log cancelled ride to history for the creator
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
                    val historyRef = firestore.collection("users").document(userId).collection("rideHistory").document()
                    transaction.set(historyRef, cancelledRideHistory)

                    ride.joinedRiders?.keys?.forEach { riderId ->
                        transaction.update(firestore.collection("users").document(riderId), "currentJoinedRide", null)
                        val notifRef = firestore.collection("users").document(riderId).collection("notifications").document()
                        transaction.set(notifRef, mapOf(
                            "actorId" to userId,
                            "createdAt" to Timestamp.now(),
                            "message" to "The ride from ${ride.origin} has been cancelled.",
                            "type" to "ride_cancelled",
                            "isRead" to false
                        ))
                    }
                }.addOnSuccessListener {
                    if (isAdded) Toast.makeText(requireContext(), "Ride cancelled", Toast.LENGTH_SHORT).show()
                }
            }

            private fun formatDuration(seconds: Double?): String {
                if (seconds == null) return "Unknown"
                val totalMinutes = (seconds / 60).toInt()
                val hours = totalMinutes / 60
                val minutes = totalMinutes % 60
                return when {
                    hours > 0 -> "$hours hr ${if (minutes > 0) "$minutes min" else ""}"
                    minutes > 0 -> "$minutes min"
                    else -> "Less than a minute"
                }
            }
        }
    }
}
