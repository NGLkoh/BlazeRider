package com.aorv.blazerider

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aorv.blazerider.databinding.FragmentMyRidesBinding
import com.aorv.blazerider.databinding.ItemOwnRidesBinding
import com.bumptech.glide.Glide
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.toObject
import java.text.SimpleDateFormat
import java.util.Locale

data class OwnRide(
    val datetime: Timestamp? = null,
    val destination: String? = null,
    val destinationCoordinates: Map<String, Double>? = null,
    val distance: Double? = null,
    val duration: Double? = null,
    val origin: String? = null,
    val originCoordinates: Map<String, Double>? = null,
    val userUid: String? = null,
    val joinedRiders: Map<String, Map<String, Any>>? = null,
    val sharedRoutesId: String? = null,
    val polyline: String? = null,
    val status: String? = null
)

class MyRidesFragment : Fragment() {

    private var _binding: FragmentMyRidesBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: MyRidesAdapter
    private val ridesList = mutableListOf<OwnRide>()
    internal val db = FirebaseFirestore.getInstance()
    internal val auth = FirebaseAuth.getInstance()
    private var ridesListener: ListenerRegistration? = null
    internal var profileImageUrl: String? = null
    internal var riderName: String? = null
    internal val TAG = "MyRidesFragment"

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMyRidesBinding.inflate(inflater, container, false)

        adapter = MyRidesAdapter(ridesList, this)
        binding.myRidesRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.myRidesRecyclerView.adapter = adapter

        fetchUserData()

        return binding.root
    }

    private fun fetchUserData() {
        val currentUserUid = auth.currentUser?.uid ?: return
        db.collection("users").document(currentUserUid).get()
            .addOnSuccessListener { document ->
                if (!isAdded) return@addOnSuccessListener
                val user = document.toObject(User::class.java)
                profileImageUrl = user?.profileImageUrl
                riderName = "${user?.firstName ?: ""} ${user?.lastName ?: ""}".trim()
                fetchMyRides()
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Failed to fetch user data", exception)
            }
    }

    private fun fetchMyRides() {
        val currentUserUid = auth.currentUser?.uid ?: return
        ridesListener = db.collection("sharedRoutes")
            .whereEqualTo("userUid", currentUserUid)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e(TAG, "Error fetching rides", error)
                    return@addSnapshotListener
                }
                if (!isAdded) return@addSnapshotListener

                ridesList.clear()
                snapshots?.forEach { document ->
                    document.toObject(OwnRide::class.java).copy(sharedRoutesId = document.id)?.let {
                        ridesList.add(it)
                    }
                }

                updateUI()
            }
    }

    fun cancelRide(ride: OwnRide) {
        val rideId = ride.sharedRoutesId ?: return
        val currentUserUid = auth.currentUser?.uid ?: return

        AlertDialog.Builder(requireContext())
            .setTitle("Cancel Ride")
            .setMessage("Are you sure you want to cancel this ride?")
            .setPositiveButton("Yes") { _, _ ->
                val rideHistoryRef = db.collection("users").document(currentUserUid)
                    .collection("rideHistory").document(rideId)

                db.runBatch { batch ->
                    batch.set(rideHistoryRef, ride.copy(status = "cancelled")) // Add to history
                    batch.delete(db.collection("sharedRoutes").document(rideId)) // Remove from shared routes
                }.addOnSuccessListener {
                    Toast.makeText(context, "Ride successfully cancelled", Toast.LENGTH_SHORT).show()
                }.addOnFailureListener { exception ->
                    Log.e(TAG, "Failed to cancel ride ID: $rideId", exception)
                }
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun updateUI() {
        activity?.runOnUiThread {
            if (ridesList.isEmpty()) {
                binding.myRidesRecyclerView.visibility = View.GONE
                binding.noRidesText.visibility = View.VISIBLE
            } else {
                binding.myRidesRecyclerView.visibility = View.VISIBLE
                binding.noRidesText.visibility = View.GONE
                adapter.notifyDataSetChanged()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ridesListener?.remove()
        _binding = null
    }
}

private class MyRidesAdapter(private val rides: List<OwnRide>, private val fragment: MyRidesFragment) :
    RecyclerView.Adapter<RideViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RideViewHolder {
        val binding = ItemOwnRidesBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return RideViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RideViewHolder, position: Int) {
        val ride = rides[position]
        val context = holder.itemView.context

        with(holder.binding) {
            riderName.text = fragment.riderName ?: "Unknown"

            if (!fragment.profileImageUrl.isNullOrEmpty()) {
                Glide.with(context)
                    .load(fragment.profileImageUrl)
                    .error(R.drawable.ic_anonymous)
                    .into(profilePicture)
            } else {
                profilePicture.setImageResource(R.drawable.ic_anonymous)
            }

            dateCreated.text = ride.datetime?.toDate()?.let {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(it)
            } ?: "Unknown"
            origin.text = "Origin: ${ride.origin ?: "Unknown"}"
            destination.text = "Destination: ${ride.destination ?: "Unknown"}"
            distance.text = ride.distance?.let { String.format("Distance: %.1f km", it) } ?: "Distance: Unknown"
            duration.text = ride.duration?.let { String.format("Duration: %.0f mins", it) } ?: "Duration: Unknown"
            val ridersCount = ride.joinedRiders?.size ?: 0
            rideNumbers.text = "$ridersCount ${if (ridersCount <= 1) "joined" else "joined"}"

            previewRideBtn.setOnClickListener {
                val intent = Intent(context, PreviewRideActivity::class.java).apply {
                    putExtra("ride_id", ride.sharedRoutesId)
                    putExtra("ride_datetime", ride.datetime?.toDate()?.time)
                    putExtra("ride_destination", ride.destination)
                    putExtra("ride_destination_lat", ride.destinationCoordinates?.get("latitude"))
                    putExtra("ride_destination_lng", ride.destinationCoordinates?.get("longitude"))
                    putExtra("ride_distance", ride.distance)
                    putExtra("ride_duration", ride.duration)
                    putExtra("ride_origin", ride.origin)
                    putExtra("ride_origin_lat", ride.originCoordinates?.get("latitude"))
                    putExtra("ride_origin_lng", ride.originCoordinates?.get("longitude"))
                    putExtra("ride_user_uid", ride.userUid)
                    putExtra("polyline", ride.polyline)
                }
                context.startActivity(intent)
            }

            cancelRideBtn.setOnClickListener {
                fragment.cancelRide(ride)
            }
        }
    }

    override fun getItemCount(): Int = rides.size
}

private class RideViewHolder(val binding: ItemOwnRidesBinding) : RecyclerView.ViewHolder(binding.root)
