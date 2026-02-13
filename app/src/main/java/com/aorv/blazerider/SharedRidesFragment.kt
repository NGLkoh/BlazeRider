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
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

                val visibleRides = rides.filter { it.status != "completed" && it.status != "cancelled" }
                
                // Show/hide "No shared rides yet" text
                noSharedRidesText.visibility = if (visibleRides.isEmpty()) View.VISIBLE else View.GONE
                
                adapter.submitList(visibleRides)
            }
    }

    /**
     * Updated Helper Function:
     * Eliminates Plus Codes by specifically selecting the street and city components.
     */
    private fun getAddressFromCoords(lat: Double?, lng: Double?): String? {
        if (lat == null || lng == null) return null
        return try {
            val geocoder = Geocoder(requireContext(), Locale.getDefault())
            val addresses: List<Address>? = geocoder.getFromLocation(lat, lng, 1)
            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0]

                // Extracting specific parts to avoid "Plus Codes"
                val street = address.thoroughfare // e.g., "Oval Road"
                val city = address.locality // e.g., "Dasmariñas"
                val subLocality = address.subLocality // neighborhood/barangay

                // Construct a clean string: "Street, City" or just "City" if street is null
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

        inner class ViewHolder(private val binding: ItemRidesBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(ride: SharedRide) {
                // 1. Fetch and display Rider info
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

                // 2. Display Ride Details
                binding.dateCreated.text = ride.datetime?.toDate()?.let {
                    SimpleDateFormat("d MMMM yyyy 'at' HH:mm:ss", Locale.getDefault()).format(it)
                } ?: "Unknown"

                // Check if origin is "Current Location" and display clean geocoded address
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

                setupButtons(ride)
            }

            private fun setupButtons(ride: SharedRide) {
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
                }

                binding.root.setOnClickListener { openPreview(ride) }
                binding.previewRideBtn.setOnClickListener { openPreview(ride) }
                binding.joinRideBtn.setOnClickListener { handleJoinClick(ride) }

                binding.startRouteBtn.setOnClickListener {
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
}