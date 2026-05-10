package com.aorv.blazerider

import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.toObject
import com.google.firebase.Timestamp
import com.bumptech.glide.Glide
import com.aorv.blazerider.databinding.ItemRidesBinding
import java.text.SimpleDateFormat
import java.util.*

class SharedRidesFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var noSharedRidesText: TextView
    private lateinit var adapter: SharedRidesAdapter
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val TAG = "SharedRidesFragment"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_shared_rides, container, false)
        recyclerView = view.findViewById(R.id.shared_rides_recycler_view)
        noSharedRidesText = view.findViewById(R.id.no_shared_rides_text)
        recyclerView.layoutManager = LinearLayoutManager(context)
        adapter = SharedRidesAdapter()
        recyclerView.adapter = adapter
        fetchSharedRides()
        return view
    }

    private fun fetchSharedRides() {
        val currentUserId = auth.currentUser?.uid
        
        firestore.collection("sharedRoutes")
            .orderBy("datetime", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error fetching shared routes: ${error.message}")
                    return@addSnapshotListener
                }
                
                val rides: List<SharedRide> = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        val ride = doc.toObject<SharedRide>()?.copy(sharedRoutesId = doc.id)
                        ride
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception parsing document ${doc.id}: ${e.message}")
                        null
                    }
                } ?: emptyList()

                val now = System.currentTimeMillis()
                val twentyFourHoursAgo = now - (24 * 60 * 60 * 1000L)

                val visibleRides = rides.filter { ride ->
                    val isStatusActive = ride.status != "completed" && ride.status != "cancelled" && ride.status != "expired"
                    val rideTime = ride.datetime?.toDate()?.time ?: 0L
                    
                    // Filter Logic:
                    // 1. Must be active (not completed/cancelled/expired)
                    // 2. Not waiting for future scheduled publication
                    // 3. Admin events and regular rides expire after 24 hours to keep feed clean
                    
                    val isScheduledWait = ride.isScheduled && rideTime > now
                    // If ride is more than 24 hours old, it's expired
                    val isExpired = rideTime < twentyFourHoursAgo
                    
                    isStatusActive && !isScheduledWait && !isExpired && ride.isPublic == true
                }
                
                // Perform background expiration for those that were filtered out due to time
                checkAndExpireRides(rides)
                
                noSharedRidesText.visibility = if (visibleRides.isEmpty()) View.VISIBLE else View.GONE
                adapter.submitList(visibleRides)
            }
    }

    private fun checkAndExpireRides(rides: List<SharedRide>) {
        val now = System.currentTimeMillis()
        val twentyFourHoursAgo = now - (24 * 60 * 60 * 1000L)

        val expiredRides = rides.filter { ride ->
            val isStatusActive = ride.status != "completed" && ride.status != "cancelled" && ride.status != "expired"
            val rideTime = ride.datetime?.toDate()?.time ?: 0L
            isStatusActive && rideTime < twentyFourHoursAgo
        }

        if (expiredRides.isEmpty()) return

        val batch = firestore.batch()
        expiredRides.forEach { ride ->
            val rideId = ride.sharedRoutesId ?: return@forEach
            
            // 1. Mark ride as expired
            batch.update(firestore.collection("sharedRoutes").document(rideId), "status", "expired")

            // 2. Log for creator
            ride.userUid?.let { creatorId ->
                val historyRef = firestore.collection("users").document(creatorId).collection("rideHistory").document()
                batch.set(historyRef, RideHistory(
                    datetime = Timestamp.now(),
                    destination = ride.destination,
                    distance = ride.distance,
                    duration = ride.duration,
                    origin = ride.origin,
                    status = "Expired",
                    userUid = creatorId,
                    sharedRoutesId = rideId
                ))
                // Clear creator status if they were still on this ride
                batch.update(firestore.collection("users").document(creatorId), "currentJoinedRide", null)

                // Notify creator
                val notifRef = firestore.collection("users").document(creatorId).collection("notifications").document()
                batch.set(notifRef, mapOf(
                    "actorId" to "system",
                    "createdAt" to Timestamp.now(),
                    "message" to "Your ride to ${ride.destination} has expired.",
                    "type" to "ride_expired",
                    "isRead" to false
                ))
            }

            // 3. Log for joined riders
            ride.joinedRiders?.keys?.forEach { riderId ->
                val historyRef = firestore.collection("users").document(riderId).collection("rideHistory").document()
                batch.set(historyRef, RideHistory(
                    datetime = Timestamp.now(),
                    destination = ride.destination,
                    distance = ride.distance,
                    duration = ride.duration,
                    origin = ride.origin,
                    status = "Expired",
                    userUid = riderId,
                    sharedRoutesId = rideId
                ))
                // Clear rider status
                batch.update(firestore.collection("users").document(riderId), "currentJoinedRide", null)
                
                // Notify rider
                val notifRef = firestore.collection("users").document(riderId).collection("notifications").document()
                batch.set(notifRef, mapOf(
                    "actorId" to "system",
                    "createdAt" to Timestamp.now(),
                    "message" to "The ride to ${ride.destination} has expired.",
                    "type" to "ride_expired",
                    "isRead" to false
                ))
            }
        }

        batch.commit().addOnFailureListener { e ->
            Log.e(TAG, "Error expiring rides: ${e.message}")
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

    inner class SharedRidesAdapter : RecyclerView.Adapter<SharedRidesAdapter.ViewHolder>() {

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

        inner class ViewHolder(private val binding: ItemRidesBinding) : ViewHolderHelper(binding) {
            fun bind(ride: SharedRide) {
                val cardView = binding.currentRideCard as? MaterialCardView
                
                if (ride.isAdminEvent) {
                    binding.currentRideCard.setCardBackgroundColor(ContextCompat.getColor(itemView.context, R.color.red_orange))
                    cardView?.strokeColor = ContextCompat.getColor(itemView.context, R.color.white)
                    cardView?.strokeWidth = 4

                    val white = ContextCompat.getColor(itemView.context, R.color.white)
                    binding.riderName.setTextColor(white as Int)
                    binding.dateCreated.setTextColor(white as Int)
                    binding.origin.setTextColor(white as Int)
                    binding.destination.setTextColor(white as Int)
                    binding.distance.setTextColor(white as Int)
                    binding.duration.setTextColor(white as Int)
                    binding.rideNumbers.setTextColor(white as Int)
                    
                    binding.originIcon.setColorFilter(white)
                    binding.destinationIcon.setColorFilter(white)
                    binding.distanceIcon.setColorFilter(white)
                    binding.durationIcon.setColorFilter(white)
                    binding.liveText.setTextColor(white as Int)
                } else {
                    binding.currentRideCard.setCardBackgroundColor(ContextCompat.getColor(itemView.context, R.color.white))
                    cardView?.strokeWidth = 0

                    binding.riderName.setTextColor(ContextCompat.getColor(itemView.context, R.color.black) as Int)
                    binding.dateCreated.setTextColor(ContextCompat.getColor(itemView.context, R.color.gray) as Int)
                    val black = ContextCompat.getColor(itemView.context, R.color.black)
                    binding.origin.setTextColor(black as Int)
                    binding.destination.setTextColor(black as Int)
                    binding.distance.setTextColor(black as Int)
                    binding.duration.setTextColor(black as Int)
                    binding.rideNumbers.setTextColor(black as Int)

                    val darkGray = ContextCompat.getColor(itemView.context, R.color.dark_gray)
                    binding.originIcon.setColorFilter(darkGray)
                    binding.destinationIcon.setColorFilter(darkGray)
                    binding.distanceIcon.setColorFilter(darkGray)
                    binding.durationIcon.setColorFilter(darkGray)
                    
                    binding.liveText.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark) as Int)
                }

                ride.userUid?.let { uid ->
                    firestore.collection("users").document(uid).get()
                        .addOnSuccessListener { userDoc ->
                            val user = userDoc.toObject<User>()
                            val firstName = user?.firstName ?: "Unknown"
                            val lastName = user?.lastName ?: "User"
                            binding.riderName.text = if (ride.isAdminEvent) "OFFICIAL EVENT: $firstName $lastName" else "$firstName $lastName"
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

                val now = System.currentTimeMillis()
                val rideTime = ride.datetime?.toDate()?.time ?: 0L
                val ridersCount = ride.joinedRiders?.size ?: 0
                
                if (ride.status == "ongoing") {
                    binding.rideNumbers.text = "$ridersCount joined"
                    binding.liveIndicator.visibility = View.VISIBLE
                    binding.liveIndicator.setBackgroundResource(R.drawable.red_pulse_dot)
                    binding.liveText.visibility = View.VISIBLE
                    binding.liveText.text = "LIVE"
                    binding.liveText.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark) as Int)
                } else if (now < rideTime) {
                    binding.rideNumbers.text = "Scheduled"
                    binding.liveIndicator.visibility = View.VISIBLE
                    binding.liveIndicator.setBackgroundResource(R.drawable.yellow_dot)
                    binding.liveText.visibility = View.VISIBLE
                    binding.liveText.text = "SCHEDULED"
                    binding.liveText.setTextColor(ContextCompat.getColor(itemView.context, R.color.orange) as Int)
                } else {
                    binding.rideNumbers.text = "$ridersCount joined"
                    binding.liveIndicator.visibility = View.GONE
                    binding.liveText.visibility = View.GONE
                }

                setupButtons(ride)
            }

            private fun setupButtons(ride: SharedRide) {
                val userId = auth.currentUser?.uid ?: return
                val isRideCreator = ride.userUid == userId
                val isRiderJoined = ride.joinedRiders?.containsKey(userId) == true

                binding.joinRideBtn.visibility = View.GONE
                binding.leaveRideBtn.visibility = View.GONE
                binding.cancelRideBtn.visibility = View.GONE
                binding.startRouteBtn.visibility = View.GONE
                binding.previewRideBtn.visibility = View.VISIBLE

                if (isRideCreator) {
                    binding.cancelRideBtn.visibility = View.VISIBLE
                    val now = System.currentTimeMillis()
                    val rideTime = ride.datetime?.toDate()?.time ?: 0L
                    
                    if (ride.status == "ongoing") {
                        binding.startRouteBtn.visibility = View.VISIBLE
                        binding.startRouteBtn.text = "Continue Route"
                    } else {
                        // Only allow starting if the ride time has arrived
                        if (now >= rideTime) {
                            binding.startRouteBtn.visibility = View.VISIBLE
                            binding.startRouteBtn.text = "Start Route"
                        } else {
                            binding.startRouteBtn.visibility = View.GONE
                        }
                    }
                } else if (isRiderJoined) {
                    binding.leaveRideBtn.visibility = View.VISIBLE
                    if (ride.status == "ongoing") {
                        binding.startRouteBtn.visibility = View.VISIBLE
                        binding.startRouteBtn.text = "Track Navigation"
                    } else {
                        binding.startRouteBtn.visibility = View.GONE
                    }
                } else {
                    binding.joinRideBtn.visibility = View.VISIBLE
                    val now = System.currentTimeMillis()
                    val rideTime = ride.datetime?.toDate()?.time ?: 0L
                    if (now < rideTime) {
                        binding.joinRideBtn.isEnabled = true
                        binding.joinRideBtn.text = "Join Scheduled Ride"
                    }
                }

                binding.root.setOnClickListener { openPreview(ride) }
                binding.previewRideBtn.setOnClickListener { openPreview(ride) }
                binding.joinRideBtn.setOnClickListener { handleJoinClick(ride) }
                binding.cancelRideBtn.setOnClickListener { showCancelConfirmation(ride) }
                binding.leaveRideBtn.setOnClickListener { showLeaveConfirmation(ride) }

                binding.startRouteBtn.setOnClickListener {
                    startNavigation(ride)
                }
            }

            private fun startNavigation(ride: SharedRide) {
                if (ride.userUid == auth.currentUser?.uid && ride.status != "ongoing") {
                    ride.sharedRoutesId?.let { id ->
                        firestore.collection("sharedRoutes").document(id)
                            .update("status", "ongoing")
                    }
                }

                val intent = Intent(requireContext(), InAppNavigationActivity::class.java).apply {
                    putExtra("EXTRA_RIDE", ride)
                }
                startActivity(intent)
            }

            private fun showCancelConfirmation(ride: SharedRide) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Cancel Ride")
                    .setMessage("Are you sure you want to cancel this ride event?")
                    .setPositiveButton("Yes") { _, _ ->
                        cancelRide(ride)
                    }
                    .setNegativeButton("No", null)
                    .show()
            }

            private fun cancelRide(ride: SharedRide) {
                val userId = auth.currentUser?.uid ?: return
                val rideId = ride.sharedRoutesId ?: return

                firestore.runTransaction { transaction ->
                    val rideRef = firestore.collection("sharedRoutes").document(rideId)
                    transaction.update(rideRef, "status", "cancelled")
                    transaction.update(firestore.collection("users").document(userId), "currentJoinedRide", null)

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
                    null
                }.addOnSuccessListener {
                    if (isAdded) Toast.makeText(requireContext(), "Ride cancelled and logged to history", Toast.LENGTH_SHORT).show()
                }.addOnFailureListener { e ->
                    Log.e(TAG, "Error cancelling ride: ${e.message}")
                    if (isAdded) Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            private fun showLeaveConfirmation(ride: SharedRide) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Leave Ride")
                    .setMessage("Are you sure you want to leave this ride?")
                    .setPositiveButton("Yes") { _, _ ->
                        val userId = auth.currentUser?.uid ?: return@setPositiveButton
                        val rideId = ride.sharedRoutesId ?: return@setPositiveButton

                        val batch = firestore.batch()
                        val rideRef = firestore.collection("sharedRoutes").document(rideId)
                        val userRef = firestore.collection("users").document(userId)

                        batch.update(rideRef, "joinedRiders.$userId", com.google.firebase.firestore.FieldValue.delete())
                        batch.update(userRef, "currentJoinedRide", null)

                        batch.commit().addOnSuccessListener {
                            if (isAdded) {
                                (activity as? SharedRidesActivity)?.hideRideBanner()
                                Toast.makeText(requireContext(), "You left the ride", Toast.LENGTH_SHORT).show()
                            }
                        }.addOnFailureListener { e ->
                            Log.e(TAG, "Error leaving ride: ${e.message}")
                        }
                    }
                    .setNegativeButton("No", null)
                    .show()
            }

            private fun openPreview(ride: SharedRide) {
                val intent = Intent(context, PreviewRideActivity::class.java).apply {
                    putExtra("ride_datetime", ride.datetime?.toDate()?.time ?: 0L)
                    putExtra("ride_destination", ride.destination)
                    putExtra("ride_destination_lat", ride.destinationCoordinates?.get("latitude"))
                    putExtra("ride_destination_lng", ride.destinationCoordinates?.get("longitude"))
                    putExtra("ride_distance", ride.distance)
                    putExtra("ride_duration", ride.duration)
                    putExtra("ride_origin", ride.origin)
                    putExtra("ride_origin_lat", ride.originCoordinates?.get("latitude"))
                    putExtra("ride_origin_lng", ride.originCoordinates?.get("longitude"))
                    putExtra("ride_user_uid", ride.userUid)
                    putExtra("ride_id", ride.sharedRoutesId)
                }
                startActivity(intent)
            }

            private fun handleJoinClick(ride: SharedRide) {
                auth.currentUser?.uid?.let { userId ->
                    firestore.collection("users").document(userId).get()
                        .addOnSuccessListener { userDoc ->
                            val currentJoinedRide = userDoc.getString("currentJoinedRide")
                            if (!currentJoinedRide.isNullOrEmpty()) {
                                Toast.makeText(requireContext(), "Finish or quit current ride first", Toast.LENGTH_SHORT).show()
                            } else {
                                showJoinRideConfirmationDialog(ride)
                            }
                        }
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

            private fun showJoinRideConfirmationDialog(ride: SharedRide) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Join Ride")
                    .setMessage("Join the ride to ${ride.destination}?")
                    .setPositiveButton("Confirm") { _, _ -> joinRide(ride) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

            private fun joinRide(ride: SharedRide) {
                val currentUser = auth.currentUser ?: return
                val rideId = ride.sharedRoutesId ?: return

                firestore.collection("users").document(currentUser.uid).get()
                    .addOnSuccessListener { userDoc ->
                        val joinerName = "${userDoc.getString("firstName")} ${userDoc.getString("lastName")}".trim()
                        val joinedRiderData = mapOf("joinedRiders.${currentUser.uid}" to mapOf("timestamp" to Timestamp.now(), "status" to "confirmed"))

                        firestore.collection("sharedRoutes").document(rideId).update(joinedRiderData).addOnSuccessListener {
                            firestore.collection("users").document(currentUser.uid).update("currentJoinedRide", rideId)
                            if (!ride.userUid.isNullOrEmpty()) {
                                val notification = mapOf(
                                    "actorId" to currentUser.uid,
                                    "createdAt" to Timestamp.now(),
                                    "message" to "$joinerName joined your ride",
                                    "type" to "ride_join"
                                )
                                firestore.collection("users").document(ride.userUid).collection("notifications").add(notification)
                            }
                            Toast.makeText(requireContext(), "Joined successfully", Toast.LENGTH_SHORT).show()
                        }
                    }
            }
        }
    }

    open inner class ViewHolderHelper(viewBinding: ItemRidesBinding) : RecyclerView.ViewHolder(viewBinding.root)
}