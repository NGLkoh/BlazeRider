package com.aorv.blazerider

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class PlaceBottomSheetFragment : BottomSheetDialogFragment() {

    private var placeName: String? = null
    private var placeAddress: String? = null
    private var onClose: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            placeName = it.getString(ARG_PLACE_NAME)
            placeAddress = it.getString(ARG_PLACE_ADDRESS)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_place_bottom_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Remove dimmed background
        dialog?.window?.setDimAmount(0f)

        // Get the BottomSheetBehavior
        val bottomSheet = (dialog as BottomSheetDialog).findViewById<View>(
            com.google.android.material.R.id.design_bottom_sheet
        )
        val behavior = BottomSheetBehavior.from(bottomSheet!!)

        // Prevent dismissal on outside touch
        behavior.isHideable = false

        // Prevent dismissal on drag
        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                    // Prevent hiding unless explicitly dismissed
                    behavior.state = BottomSheetBehavior.STATE_EXPANDED
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {}
        })

        // Initialize views
        val nameTextView = view.findViewById<TextView>(R.id.place_name)
        val addressTextView = view.findViewById<TextView>(R.id.place_address)
        val closeButton = view.findViewById<ImageView>(R.id.close_button)
        val addDestinationButton = view.findViewById<Button>(R.id.add_destination_button)
        val setStartButton = view.findViewById<Button>(R.id.set_start_button)
        val shareRideButton = view.findViewById<Button>(R.id.share_ride_button)

        // Set place details
        nameTextView.text = placeName
        addressTextView.text = placeAddress

        // Close button listener
        closeButton.setOnClickListener {
            onClose?.invoke()
            dismiss()
        }

        // Button listeners (placeholders)
        addDestinationButton.setOnClickListener {
            Toast.makeText(context, "Add destination: $placeName", Toast.LENGTH_SHORT).show()
            // TODO: Implement add destination logic
        }

        setStartButton.setOnClickListener {
            Toast.makeText(context, "Set as start: $placeName", Toast.LENGTH_SHORT).show()
            // TODO: Implement set start location logic
        }

        shareRideButton.setOnClickListener {
            Toast.makeText(context, "Share ride: $placeName", Toast.LENGTH_SHORT).show()
            // TODO: Implement share ride logic
        }

        // Prevent map touch events from dismissing bottom sheet
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                // Consume touch events to prevent them from reaching the map
                true
            } else {
                false
            }
        }
    }

    companion object {
        const val TAG = "PlaceBottomSheetFragment"
        private const val ARG_PLACE_NAME = "place_name"
        private const val ARG_PLACE_ADDRESS = "place_address"

        fun newInstance(placeName: String, placeAddress: String, onClose: () -> Unit): PlaceBottomSheetFragment {
            return PlaceBottomSheetFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PLACE_NAME, placeName)
                    putString(ARG_PLACE_ADDRESS, placeAddress)
                }
                this.onClose = onClose
            }
        }
    }
}