package com.aorv.blazerider

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.aorv.blazerider.databinding.ActivityHistoryBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.toObjects

class HistoryFragment : Fragment() {

    private var _binding: ActivityHistoryBinding? = null
    private val binding get() = _binding!!

    private lateinit var historyAdapter: HistoryAdapter
    private val historyList = mutableListOf<RideHistory>()
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivityHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.clearAllButton.setOnClickListener {
            showClearHistoryConfirmation()
        }

        setupRecyclerView()
        fetchRideHistory()
    }

    private fun setupRecyclerView() {
        historyAdapter = HistoryAdapter(historyList)
        binding.historyRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = historyAdapter
        }
    }

    private fun fetchRideHistory() {
        val currentUserUid = auth.currentUser?.uid ?: return

        binding.progressBar.visibility = View.VISIBLE

        db.collection("users").document(currentUserUid).collection("rideHistory")
            .orderBy("datetime", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, e ->
                if (_binding == null) return@addSnapshotListener
                binding.progressBar.visibility = View.GONE

                if (e != null) {
                    Log.w("HistoryFragment", "Listen failed.", e)
                    binding.noHistoryText.text = "Failed to load history."
                    binding.noHistoryText.visibility = View.VISIBLE
                    binding.historyRecyclerView.visibility = View.GONE
                    binding.clearAllButton.visibility = View.GONE
                    return@addSnapshotListener
                }

                if (snapshot != null && !snapshot.isEmpty) {
                    val rides = snapshot.toObjects<RideHistory>()
                    historyList.clear()
                    historyList.addAll(rides)
                    historyAdapter.notifyDataSetChanged()

                    binding.historyRecyclerView.visibility = View.VISIBLE
                    binding.noHistoryText.visibility = View.GONE
                    binding.clearAllButton.visibility = View.VISIBLE
                } else {
                    historyList.clear()
                    historyAdapter.notifyDataSetChanged()
                    binding.historyRecyclerView.visibility = View.GONE
                    binding.noHistoryText.visibility = View.VISIBLE
                    binding.clearAllButton.visibility = View.GONE
                    Log.d("HistoryFragment", "Current data: null or empty")
                }
            }
    }

    private fun showClearHistoryConfirmation() {
        if (historyList.isEmpty()) {
            Toast.makeText(requireContext(), "No history to clear.", Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Clear History")
            .setMessage("Are you sure you want to delete all your ride history? This action cannot be undone.")
            .setPositiveButton("Clear All") { _, _ ->
                clearAllHistory()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearAllHistory() {
        val currentUserUid = auth.currentUser?.uid ?: return
        
        binding.progressBar.visibility = View.VISIBLE
        
        val rideHistoryRef = db.collection("users").document(currentUserUid).collection("rideHistory")
        
        rideHistoryRef.get().addOnSuccessListener { snapshot ->
            if (snapshot.isEmpty) {
                if (_binding != null) binding.progressBar.visibility = View.GONE
                Toast.makeText(requireContext(), "History is already empty.", Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }

            val batch = db.batch()
            for (doc in snapshot.documents) {
                batch.delete(doc.reference)
            }

            batch.commit()
                .addOnSuccessListener {
                    if (_binding != null) {
                        binding.progressBar.visibility = View.GONE
                        Log.d("HistoryFragment", "All history successfully deleted.")
                        Toast.makeText(requireContext(), "History cleared", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener { e ->
                    if (_binding != null) {
                        binding.progressBar.visibility = View.GONE
                        Log.w("HistoryFragment", "Error deleting history.", e)
                        Toast.makeText(requireContext(), "Failed to clear history.", Toast.LENGTH_SHORT).show()
                    }
                }
        }.addOnFailureListener { e ->
            if (_binding != null) {
                binding.progressBar.visibility = View.GONE
                Log.e("HistoryFragment", "Error fetching history for deletion", e)
                Toast.makeText(requireContext(), "Failed to access history.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
