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
        firestore.collection("sharedRoutes")
            .orderBy("datetime", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Error fetching shared routes: ${error.message}")
                    return@addSnapshotListener
                }
                
                val rides: List<SharedRide> = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        doc.toObject<SharedRide>()?.copy(sharedRoutesId = doc.id)
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception parsing document ${doc.id}: ${e.message}")
                        null
                    }
                } ?: emptyList()

                val now = Timestamp.now()
                val visibleRides = rides.filter { ride ->
                    val isStatusActive = ride.status != "completed" && ride.status != "cancelled"
                    
                    // Logic: Visible if NOT scheduled OR if it IS scheduled but the publish time (createdAt) has passed
                    val isVisible = !ride.isScheduled || (ride.createdAt != null && ride.createdAt.seconds <= now.seconds)
                    
                    isStatusActive && isVisible
                }
                
                noSharedRidesText.visibility = if (visibleRides.isEmpty()) View.VISIBLE else View.GONE
                adapter.submitList(visibleRides)
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
                
                // 1. Background and Border logic for Admin Events
                if (ride.isAdminEvent) {
                    binding.currentRideCard.setCardBackgroundColor(ContextCompat.getColor(itemView.context, R.color.red_orange))
                    
                    // Add Border
                    cardView?.strokeColor = ContextCompat.getColor(itemView.context, R.color.white)
                    cardView?.strokeWidth = 4 // 4px border

                    // Set all text and icons to white
                    val white = ContextCompat.getColor(itemView.context, R.color.white)
                    binding.riderName.setTextColor(white)
                    binding.dateCreated.setTextColor(white)
                    binding.origin.setTextColor(white)
                    binding.destination.setTextColor(white)
                    binding.distance.setTextColor(white)
                    binding.duration.setTextColor(white)
                    binding.rideNumbers.setTextColor(white)
                    
                    binding.originIcon.setColorFilter(white)
                    binding.destinationIcon.setColorFilter(white)
                    binding.distanceIcon.setColorFilter(white)
                    binding.durationIcon.setColorFilter(white)
                    
                    // Also set live text to white if needed
                    binding.liveText.setTextColor(white)
                } else {
                    // Standard User Card
                    binding.currentRideCard.setCardBackgroundColor(ContextCompat.getColor(itemView.context, R.color.white))
                    
                    // Remove Border
                    cardView?.strokeWidth = 0

                    binding.riderName.setTextColor(ContextCompat.getColor(itemView.context, R.color.black))
                    binding.dateCreated.setTextColor(ContextCompat.getColor(itemView.context, R.color.gray))
                    val black = ContextCompat.getColor(itemView.context, R.color.black)
                    binding.origin.setTextColor(black)
                    binding.destination.setTextColor(black)
                    binding.distance.setTextColor(black)
                    binding.duration.setTextColor(black)
                    binding.rideNumbers.setTextColor(black)

                    val darkGray = ContextCompat.getColor(itemView.context, R.color.dark_gray)
                    binding.originIcon.setColorFilter(darkGray)
                    binding.destinationIcon.setColorFilter(darkGray)
                    binding.distanceIcon.setColorFilter(darkGray)
                    binding.durationIcon.setColorFilter(darkGray)
                    
                    binding.liveText.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark))
                }

                // 2. Load User Info
                ride.userUid?.let { uid ->
                    firestore.collection("users").document(uid).get()
                        .addOnSuccessListener { userDoc ->
                            val user = userDoc.toObject<User>()
                            binding.riderName.text = if (ride.isAdminEvent) "OFFICIAL EVENT: ${user?.firstName} ${user?.lastName}" else "${user?.firstName} ${user?.lastName}"
                            Glide.with(binding.profilePicture.context)
                                .load(user?.profileImageUrl ?: R.drawable.ic_anonymous)
                                .into(binding.profilePicture)
                        }
                }

                // 3. Display Data
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
                binding.rideNumbers.text = "$ridersCount joined"

                // 4. Handle Ongoing Indicator
                if (ride.status == "ongoing") {
                    binding.liveIndicator.visibility = View.VISIBLE
                    binding.liveText.visibility = View.VISIBLE
                } else {
                    binding.liveIndicator.visibility = View.GONE
                    binding.liveText.visibility = View.GONE
                }

                setupButtons(ride)
            }

            private fun setupButtons(ride: SharedRide) {
                val userId = auth.currentUser?.uid ?: return
                val isRideCreator = ride.userUid == userId
                val isRiderJoined = ride.joinedRiders?.containsKey(userId) == true

                // Reset visibilities using camelCase names generated by ViewBinding
                binding.joinRideBtn.visibility = View.GONE
                binding.leaveRideBtn.visibility = View.GONE
                binding.cancelRideBtn.visibility = View.GONE
                binding.startRouteBtn.visibility = View.GONE
                binding.previewRideBtn.visibility = View.VISIBLE
                binding.arrivedBtn.visibility = View.GONE

                if (isRideCreator) {
                    // Creator can Cancel or Start/Arrive
                    binding.cancelRideBtn.visibility = View.VISIBLE
                    if (ride.status == "ongoing") {
                        binding.arrivedBtn.visibility = View.VISIBLE
                        binding.startRouteBtn.visibility = View.VISIBLE // Re-open nav
                        binding.startRouteBtn.text = "Continue Route"
                    } else {
                        binding.startRouteBtn.visibility = View.VISIBLE
                        binding.startRouteBtn.text = "Start Route"
                    }
                } else if (isRiderJoined) {
                    // Joined rider can Leave
                    binding.leaveRideBtn.visibility = View.VISIBLE
                    if (ride.status == "ongoing") {
                        binding.startRouteBtn.visibility = View.VISIBLE
                    }
                } else {
                    // Others can Join
                    binding.joinRideBtn.visibility = View.VISIBLE
                }

                // Listeners using camelCase names
                binding.root.setOnClickListener { openPreview(ride) }
                binding.previewRideBtn.setOnClickListener { openPreview(ride) }
                binding.joinRideBtn.setOnClickListener { handleJoinClick(ride) }
                binding.cancelRideBtn.setOnClickListener { showCancelConfirmation(ride) }
                binding.leaveRideBtn.setOnClickListener { showLeaveConfirmation(ride) }
                binding.arrivedBtn.setOnClickListener { showArrivedConfirmation(ride) }

                binding.startRouteBtn.setOnClickListener {
                    startNavigation(ride)
                }
            }

            private fun startNavigation(ride: SharedRide) {
                // If creator starts, mark as ongoing
                if (ride.userUid == auth.currentUser?.uid && ride.status != "ongoing") {
                    ride.sharedRoutesId?.let { id ->
                        firestore.collection("sharedRoutes").document(id)
                            .update("status", "ongoing")
                    }
                }

                val lat = ride.destinationCoordinates?.get("latitude")
                val lng = ride.destinationCoordinates?.get("longitude")
                if (lat != null && lng != null) {
                    val gmmIntentUri = Uri.parse("google.navigation:q=$lat,$lng")
                    val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                    mapIntent.setPackage("com.google.android.apps.maps")
                    if (mapIntent.resolveActivity(requireContext().packageManager) != null) {
                        startActivity(mapIntent)
                    } else {
                        startActivity(Intent(Intent.ACTION_VIEW, gmmIntentUri))
                    }
                }
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
                        ride.sharedRoutesId?.let { id ->
                            val updates = hashMapOf<String, Any>(
                                "joinedRiders.$userId" to com.google.firebase.firestore.FieldValue.delete()
                            )
                            firestore.collection("sharedRoutes").document(id).update(updates)
                        }
                    }
                    .setNegativeButton("No", null)
                    .show()
            }
            
            private fun showArrivedConfirmation(ride: SharedRide) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Arrived")
                    .setMessage("Have you reached the destination?")
                    .setPositiveButton("Yes") { _, _ ->
                        completeRide(ride)
                    }
                    .setNegativeButton("No", null)
                    .show()
            }

            private fun completeRide(ride: SharedRide) {
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

                val rideRef = firestore.collection("sharedRoutes").document(rideId)
                batch.update(rideRef, "status", "completed")

                batch.commit().addOnSuccessListener {
                    if (isAdded) {
                        Toast.makeText(requireContext(), "Ride completed!", Toast.LENGTH_SHORT).show()
                    }
                }.addOnFailureListener { e ->
                    Log.e(TAG, "Error completing ride: ${e.message}")
                }
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
