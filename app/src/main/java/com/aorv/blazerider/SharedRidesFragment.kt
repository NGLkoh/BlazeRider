package com.aorv.blazerider

import android.content.Intent
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

data class SharedRide(
    val datetime: Timestamp? = null,
    val destination: String? = null,
    val destinationCoordinates: Map<String, Double>? = null,
    val distance: Double? = null,
    val duration: Double? = null,
    val origin: String? = null,
    val originCoordinates: Map<String, Double>? = null,
    val userUid: String? = null,
    val joinedRiders: Map<String, Map<String, Any>>? = null,
    val sharedRoutesId: String? = null
)

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
                adapter.submitList(rides)
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
                binding.rideNumbers.text = "$ridersCount ${if (ridersCount <= 1) "joined" else "joined"}"

                // Check if user is already in a ride
                auth.currentUser?.uid?.let { userId ->
                    if (ride.userUid == userId) {
                        binding.joinRideBtn.visibility = View.GONE
                    } else {
                        binding.joinRideBtn.visibility = View.VISIBLE
                    }

                    firestore.collection("users").document(userId).get()
                        .addOnSuccessListener { userDoc ->
                            val currentJoinedRide = userDoc.getString("currentJoinedRide")
                            val isInRide = currentJoinedRide != null && currentJoinedRide != ride.sharedRoutesId
                            binding.joinRideBtn.isEnabled = !isInRide
                            binding.joinRideBtn.alpha = if (isInRide) 0.5f else 1.0f // Gray out when disabled
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Error checking currentJoinedRide: ${e.message}")
                            binding.joinRideBtn.isEnabled = false
                            binding.joinRideBtn.alpha = 0.5f
                        }
                }

                // Preview Ride button
                binding.previewRideBtn.setOnClickListener {
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

                // Join Ride button
                binding.joinRideBtn.setOnClickListener {
                    auth.currentUser?.uid?.let { userId ->
                        firestore.collection("users").document(userId).get()
                            .addOnSuccessListener { userDoc ->
                                val currentJoinedRide = userDoc.getString("currentJoinedRide")
                                if (currentJoinedRide != null && currentJoinedRide != ride.sharedRoutesId) {
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
                                Log.d(TAG, "Successfully joined ride $rideId")
                                android.widget.Toast.makeText(requireContext(), "Joined ride successfully", android.widget.Toast.LENGTH_SHORT).show()
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
