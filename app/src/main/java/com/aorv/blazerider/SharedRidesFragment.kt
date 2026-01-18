package com.aorv.blazerider

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.toObject
import com.google.android.material.imageview.ShapeableImageView
import com.google.firebase.Timestamp
import com.bumptech.glide.Glide
import com.aorv.blazerider.databinding.ItemRidesBinding
import java.text.SimpleDateFormat
import java.util.*
import com.aorv.blazerider.User

class SharedRidesFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
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
                
                // Hide completed and cancelled rides from the shared feed
                val visibleRides = rides.filter { it.status != "completed" && it.status != "cancelled" }
                adapter.submitList(visibleRides)
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
            val ride = rides[position]
            holder.bind(ride)
        }

        override fun getItemCount(): Int = rides.size

        inner class ViewHolder(private val binding: ItemRidesBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(ride: SharedRide) {
                // Fetch user data
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

                // Populate ride data
                binding.dateCreated.text = ride.datetime?.toDate()?.let {
                    SimpleDateFormat("d MMMM yyyy 'at' HH:mm:ss", Locale.getDefault()).format(it)
                } ?: "Unknown"
                binding.origin.text = "Origin: ${ride.origin}"
                binding.destination.text = "Destination: ${ride.destination}"
                binding.distance.text = "Distance: ${ride.distance?.let { String.format("%.2f km", it) } ?: "Unknown"}"
                binding.duration.text = "Duration: ${formatDuration(ride.duration)}"
                val ridersCount = ride.joinedRiders?.size ?: 0
                binding.rideNumbers.text = "$ridersCount joined"

                // Check if user is already in a ride
                auth.currentUser?.uid?.let { userId ->
                    val isRideCreator = ride.userUid == userId
                    val isRiderJoined = ride.joinedRiders?.containsKey(userId) == true

                    if (isRideCreator || isRiderJoined) {
                        binding.joinRideBtn.visibility = View.GONE
                        binding.previewRideBtn.visibility = View.VISIBLE
                        binding.startRouteBtn.visibility = View.VISIBLE
                    } else {
                        binding.joinRideBtn.visibility = View.VISIBLE
                        binding.previewRideBtn.visibility = View.VISIBLE
                        binding.startRouteBtn.visibility = View.GONE
                    }

                    firestore.collection("users").document(userId).get()
                        .addOnSuccessListener { userDoc ->
                            val currentJoinedRide = userDoc.getString("currentJoinedRide")
                            val canJoin = currentJoinedRide.isNullOrEmpty()
                            // Keep it enabled so the user can click it to see the feedback Toast
                            binding.joinRideBtn.isEnabled = true
                            binding.joinRideBtn.alpha = if (canJoin) 1.0f else 0.5f
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Error checking currentJoinedRide: ${e.message}")
                            binding.joinRideBtn.isEnabled = true
                        }
                }

                // Define preview action
                val openPreview = {
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

                // Make the whole card clickable
                binding.root.setOnClickListener { openPreview() }

                // Preview Ride button
                binding.previewRideBtn.setOnClickListener { openPreview() }

                // Join Ride button
                binding.joinRideBtn.setOnClickListener {
                    auth.currentUser?.uid?.let { userId ->
                        firestore.collection("users").document(userId).get()
                            .addOnSuccessListener { userDoc ->
                                val currentJoinedRide = userDoc.getString("currentJoinedRide")
                                if (!currentJoinedRide.isNullOrEmpty()) {
                                    android.widget.Toast.makeText(
                                        requireContext(),
                                        "You must finish or quit your current ride before joining a new one",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    showJoinRideConfirmationDialog(ride)
                                }
                            }
                            .addOnFailureListener { e ->
                                Log.e(TAG, "Error checking currentJoinedRide: ${e.message}")
                                android.widget.Toast.makeText(
                                    requireContext(),
                                    "Error: ${e.message}",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                    }
                }

                // Start Route button
                binding.startRouteBtn.setOnClickListener {
                    val destLat = ride.destinationCoordinates?.get("latitude")
                    val destLng = ride.destinationCoordinates?.get("longitude")

                    if (destLat != null && destLng != null) {
                        val originLat = ride.originCoordinates?.get("latitude")
                        val originLng = ride.originCoordinates?.get("longitude")

                        val uriString = if (originLat != null && originLng != null) {
                            "http://maps.google.com/maps?saddr=${originLat},${originLng}&daddr=${destLat},${destLng}"
                        } else {
                            "http://maps.google.com/maps?daddr=${destLat},${destLng}"
                        }
                        
                        val gmmIntentUri = Uri.parse(uriString)
                        val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                        mapIntent.setPackage("com.google.android.apps.maps")
                        
                        if (mapIntent.resolveActivity(requireContext().packageManager) != null) {
                            startActivity(mapIntent)
                        } else {
                             // If Google Maps is not installed, open the URI in a browser
                            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString))
                            startActivity(browserIntent)
                        }
                    } else {
                        android.widget.Toast.makeText(requireContext(), "Destination not set for this ride.", android.widget.Toast.LENGTH_SHORT).show()
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
                    .setMessage("Are you sure you want to join the ride to ${ride.destination}?")
                    .setPositiveButton("Confirm") { _, _ ->
                        joinRide(ride)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

            private fun joinRide(ride: SharedRide) {
                val currentUser = auth.currentUser ?: return
                val rideId = ride.sharedRoutesId ?: return
                
                firestore.collection("users").document(currentUser.uid).get()
                    .addOnSuccessListener { userDoc ->
                        val firstName = userDoc.getString("firstName") ?: ""
                        val lastName = userDoc.getString("lastName") ?: ""
                        val joinerName = "$firstName $lastName".trim()

                        val joinedRiderData = mapOf(
                            "joinedRiders.${currentUser.uid}" to mapOf(
                                "timestamp" to Timestamp.now(),
                                "status" to "confirmed"
                            )
                        )

                        // Update sharedRoutes/{sharedRoutesId}/joinedRiders
                        firestore.collection("sharedRoutes").document(rideId)
                            .update(joinedRiderData)
                            .addOnSuccessListener {
                                // Update users/{userId}/currentJoinedRide
                                firestore.collection("users").document(currentUser.uid)
                                    .update("currentJoinedRide", rideId)
                                    .addOnSuccessListener {
                                        Log.d(TAG, "Successfully joined ride ${rideId}")
                                        android.widget.Toast.makeText(requireContext(), "Joined ride successfully", android.widget.Toast.LENGTH_SHORT).show()
                                        
                                        // Send notification to the ride creator
                                        if (!ride.userUid.isNullOrEmpty()) {
                                            val notification = Notification(
                                                actorId = currentUser.uid,
                                                createdAt = Timestamp.now(),
                                                entityId = rideId,
                                                entityType = "ride",
                                                message = "$joinerName joined your ride from ${ride.origin} to ${ride.destination}",
                                                type = "ride_join",
                                                updatedAt = Timestamp.now()
                                            )
                                            firestore.collection("users").document(ride.userUid)
                                                .collection("notifications").add(notification)
                                        }
                                    }
                                    .addOnFailureListener { e ->
                                        Log.e(TAG, "Error updating currentJoinedRide: ${e.message}")
                                        android.widget.Toast.makeText(requireContext(), "Failed to join ride: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                            }
                            .addOnFailureListener { e ->
                                Log.e(TAG, "Error joining ride: ${e.message}")
                                android.widget.Toast.makeText(requireContext(), "Failed to join ride: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                    }
            }
        }
    }
}
