package com.aorv.blazerider

import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import com.aorv.blazerider.databinding.FragmentInAppNavigationBinding
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.util.*

class InAppNavigationFragment : Fragment() {

    private var _binding: FragmentInAppNavigationBinding? = null
    private val binding get() = _binding!!

    private val locationViewModel: LocationViewModel by activityViewModels()
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val TAG = "InAppNavFragment"

    private var currentRide: SharedRide? = null
    private var hasArrived = false
    private var locationObserver: Observer<LatLng?>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            @Suppress("DEPRECATION")
            currentRide = it.getParcelable(ARG_RIDE)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentInAppNavigationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        currentRide?.let { ride ->
            binding.textDestination.text = ride.destination ?: "Unknown"

            val userId = auth.currentUser?.uid ?: return

            locationObserver = Observer { lastKnownLatLng ->
                if (lastKnownLatLng != null && !hasArrived) {
                    binding.textCurrentLocation.text = "Lat: ${lastKnownLatLng.latitude}, Lng: ${lastKnownLatLng.longitude}"

                    val destLat = ride.destinationCoordinates?.get("latitude") ?: 0.0
                    val destLng = ride.destinationCoordinates?.get("longitude") ?: 0.0

                    val currentLocationForDistance = Location("").apply {
                        latitude = lastKnownLatLng.latitude
                        longitude = lastKnownLatLng.longitude
                    }
                    val destinationLocationForDistance = Location("").apply {
                        latitude = destLat
                        longitude = destLng
                    }

                    val distanceInMeters = currentLocationForDistance.distanceTo(destinationLocationForDistance)
                    binding.textDistanceRemaining.text = String.format("%.2f meters", distanceInMeters)

                    // Live location update to Firebase
                    val liveLocationData = hashMapOf(
                        "latitude" to lastKnownLatLng.latitude,
                        "longitude" to lastKnownLatLng.longitude,
                        "bearing" to 0.0f
                    )

                    ride.sharedRoutesId?.let { rideId ->
                        firestore.collection("sharedRoutes")
                            .document(rideId)
                            .collection("liveLocations")
                            .document(userId)
                            .set(liveLocationData)
                    }

                    // Check for arrival
                    if (distanceInMeters < 50) {
                        binding.textStatus.text = "Status: Arrived!"
                        binding.textStatus.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark))

                        hasArrived = true
                        completeRide(ride)
                    }
                }
            }
            locationViewModel.lastKnownLocation.observe(viewLifecycleOwner, locationObserver!!)
        }
    }

    private fun completeRide(ride: SharedRide) {
        val userId = auth.currentUser?.uid ?: return
        val rideId = ride.sharedRoutesId ?: return
        val isRideCreator = ride.userUid == userId

        val batch = firestore.batch()

        val userRef = firestore.collection("users").document(userId)
        batch.update(userRef, "currentJoinedRide", null)

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
        val historyRef = firestore.collection("users").document(userId).collection("rideHistory").document()
        batch.set(historyRef, rideHistory)

        val rideRef = firestore.collection("sharedRoutes").document(rideId)
        if (isRideCreator) {
            batch.update(rideRef, "status", "completed")
        } else {
            batch.update(rideRef, "joinedRiders.$userId", FieldValue.delete())
        }

        val arrivalNotifRef = firestore.collection("users").document(userId).collection("notifications").document()
        batch.set(arrivalNotifRef, mapOf(
            "actorId" to userId,
            "createdAt" to Timestamp.now(),
            "message" to "You have arrived at your destination for the ride to ${ride.destination}.",
            "type" to "ride_arrived",
            "isRead" to false
        ))

        batch.commit().addOnSuccessListener {
            if (isAdded) {
                Toast.makeText(requireContext(), "Ride completed!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_RIDE = "arg_ride"

        @JvmStatic
        fun newInstance(ride: SharedRide) = InAppNavigationFragment().apply {
            arguments = Bundle().apply {
                putParcelable(ARG_RIDE, ride)
            }
        }
    }
}