package com.aorv.blazerider

import android.location.Address
import android.location.Geocoder
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aorv.blazerider.databinding.ItemRidesBinding
import com.bumptech.glide.Glide
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.toObject
import java.text.SimpleDateFormat
import java.util.*

class AdminHistoryFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var noHistoryText: TextView
    private lateinit var btnClearHistory: Button
    private lateinit var loadingProgress: ProgressBar
    private lateinit var adapter: HistoryAdapter
    private val firestore = FirebaseFirestore.getInstance()
    private val TAG = "AdminHistoryFragment"
    private var historyListener: ListenerRegistration? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_admin_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.history_recycler_view)
        noHistoryText = view.findViewById(R.id.no_history_text)
        btnClearHistory = view.findViewById(R.id.btn_clear_history)
        loadingProgress = view.findViewById(R.id.history_loading_progress)

        recyclerView.layoutManager = LinearLayoutManager(context)
        adapter = HistoryAdapter()
        recyclerView.adapter = adapter

        btnClearHistory.setOnClickListener {
            showClearHistoryConfirmation()
        }

        fetchAdminHistory()
    }

    private fun showClearHistoryConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle("Clear History")
            .setMessage("Are you sure you want to clear all event history? This action cannot be undone.")
            .setPositiveButton("Clear All") { _, _ ->
                performClear()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performClear() {
        loadingProgress.visibility = View.VISIBLE
        
        firestore.collection("sharedRoutes")
            .whereEqualTo("isAdminEvent", true)
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.isEmpty) {
                    loadingProgress.visibility = View.GONE
                    Toast.makeText(context, "No history to clear", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                val batch = firestore.batch()
                var count = 0
                for (doc in snapshot.documents) {
                    val status = doc.getString("status")?.lowercase(Locale.getDefault())
                    if (status == "completed" || status == "cancelled" || status == null) {
                        batch.delete(doc.reference)
                        count++
                    }
                }
                
                if (count > 0) {
                    batch.commit().addOnSuccessListener {
                        if (isAdded) {
                            Toast.makeText(context, "History cleared successfully", Toast.LENGTH_SHORT).show()
                        }
                    }.addOnFailureListener { e ->
                        if (isAdded) {
                            loadingProgress.visibility = View.GONE
                            Toast.makeText(context, "Failed to clear: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    loadingProgress.visibility = View.GONE
                    if (isAdded) Toast.makeText(context, "No history found to clear", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                if (isAdded) {
                    loadingProgress.visibility = View.GONE
                    Toast.makeText(context, "Error fetching history: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun fetchAdminHistory() {
        historyListener?.remove()
        
        loadingProgress.visibility = View.VISIBLE
        noHistoryText.visibility = View.GONE
        btnClearHistory.visibility = View.GONE

        // Removed the .whereEqualTo filter to avoid the "FAILED_PRECONDITION" index error.
        // We filter client-side instead for immediate results.
        historyListener = firestore.collection("sharedRoutes")
            .orderBy("datetime", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (!isAdded) return@addSnapshotListener

                loadingProgress.visibility = View.GONE

                if (error != null) {
                    Log.e(TAG, "Error fetching history: ${error.message}")
                    recyclerView.visibility = View.GONE
                    noHistoryText.visibility = View.VISIBLE
                    noHistoryText.text = "Error loading history: ${error.localizedMessage}"
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val historyList = snapshot.documents.mapNotNull { doc ->
                        try {
                            val ride = doc.toObject<SharedRide>()?.copy(sharedRoutesId = doc.id)
                            
                            // Check if it's an admin event (client-side filter)
                            val isAdminEvent = doc.getBoolean("isAdminEvent") ?: false
                            val status = ride?.status?.lowercase(Locale.getDefault())
                            
                            // Only include completed or cancelled official events
                            if (isAdminEvent && (status == "completed" || status == "cancelled")) {
                                ride
                            } else {
                                null
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing ride ${doc.id}: ${e.message}")
                            null
                        }
                    }

                    adapter.submitList(historyList)

                    if (historyList.isEmpty()) {
                        recyclerView.visibility = View.GONE
                        noHistoryText.visibility = View.VISIBLE
                        noHistoryText.text = "No official event history found."
                        btnClearHistory.visibility = View.GONE
                    } else {
                        recyclerView.visibility = View.VISIBLE
                        noHistoryText.visibility = View.GONE
                        btnClearHistory.visibility = View.VISIBLE
                    }
                }
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        historyListener?.remove()
    }

    inner class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {
        private var items: List<SharedRide> = emptyList()

        fun submitList(newItems: List<SharedRide>) {
            val diffCallback = object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = items.size
                override fun getNewListSize(): Int = newItems.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return items[oldItemPosition].sharedRoutesId == newItems[newItemPosition].sharedRoutesId
                }
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return items[oldItemPosition] == newItems[newItemPosition]
                }
            }
            val diffResult = DiffUtil.calculateDiff(diffCallback)
            items = newItems
            diffResult.dispatchUpdatesTo(this)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemRidesBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(private val binding: ItemRidesBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(ride: SharedRide) {
                ride.userUid?.let { uid ->
                    firestore.collection("users").document(uid).get()
                        .addOnSuccessListener { userDoc ->
                            if (!isAdded) return@addOnSuccessListener
                            val firstName = userDoc.getString("firstName") ?: "Admin"
                            val lastName = userDoc.getString("lastName") ?: ""
                            val profileImageUrl = userDoc.getString("profileImageUrl")
                            
                            binding.riderName.text = "OFFICIAL: $firstName $lastName"
                            Glide.with(binding.profilePicture.context)
                                .load(profileImageUrl ?: R.drawable.ic_anonymous)
                                .into(binding.profilePicture)
                        }
                }

                binding.dateCreated.text = ride.datetime?.toDate()?.let {
                    SimpleDateFormat("d MMMM yyyy 'at' hh:mm a", Locale.getDefault()).format(it)
                } ?: "Unknown"

                val rawOrigin = ride.origin ?: "Unknown"
                if (rawOrigin.equals("Current Location", ignoreCase = true)) {
                    val lat = ride.originCoordinates?.get("latitude") as? Double
                    val lng = ride.originCoordinates?.get("longitude") as? Double
                    binding.origin.text = "Origin: ${getAddressFromCoords(lat, lng) ?: rawOrigin}"
                } else {
                    binding.origin.text = "Origin: $rawOrigin"
                }

                binding.destination.text = "Destination: ${ride.destination}"
                binding.distance.text = "Distance: ${ride.distance?.let { String.format("%.2f km", it) } ?: "Unknown"}"
                binding.duration.text = "Duration: ${formatDuration(ride.duration)}"

                binding.liveIndicator.visibility = View.VISIBLE
                binding.liveText.visibility = View.VISIBLE
                
                val status = ride.status?.lowercase(Locale.getDefault())
                when (status) {
                    "completed" -> {
                        binding.liveText.text = "COMPLETED"
                        binding.liveText.setTextColor(android.graphics.Color.parseColor("#388E3C"))
                        binding.liveIndicator.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#388E3C"))
                    }
                    "cancelled" -> {
                        binding.liveText.text = "CANCELLED"
                        binding.liveText.setTextColor(android.graphics.Color.RED)
                        binding.liveIndicator.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.RED)
                    }
                    else -> {
                        binding.liveText.text = status?.uppercase(Locale.getDefault()) ?: "FINISHED"
                        binding.liveText.setTextColor(android.graphics.Color.GRAY)
                        binding.liveIndicator.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.GRAY)
                    }
                }

                binding.startRouteBtn.visibility = View.GONE
                binding.joinRideBtn.visibility = View.GONE
                binding.previewRideBtn.visibility = View.GONE
                binding.leaveRideBtn.visibility = View.GONE
                binding.cancelRideBtn.visibility = View.GONE
                binding.viewRidersBtn.visibility = View.GONE
                
                val ridersCount = ride.joinedRiders?.size ?: 0
                binding.rideNumbers.text = "$ridersCount riders were joined"
            }

            private fun getAddressFromCoords(lat: Double?, lng: Double?): String? {
                if (lat == null || lng == null) return null
                return try {
                    val geocoder = Geocoder(itemView.context, Locale.getDefault())
                    val addresses: List<Address>? = geocoder.getFromLocation(lat, lng, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val address = addresses[0]
                        address.thoroughfare ?: address.locality ?: address.subAdminArea ?: "Unknown Location"
                    } else null
                } catch (e: Exception) { null }
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
