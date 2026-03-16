package com.aorv.blazerider

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.aorv.blazerider.databinding.ActivityHistoryBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.toObjects

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var historyAdapter: HistoryAdapter
    private val historyList = mutableListOf<RideHistory>()
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Using the toolbar directly instead of setSupportActionBar to avoid event conflicts
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        binding.clearAllButton.setOnClickListener {
            showClearHistoryConfirmation()
        }

        setupRecyclerView()
        setupFirestoreListener()
    }

    private fun setupRecyclerView() {
        historyAdapter = HistoryAdapter(historyList)
        binding.historyRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
            adapter = historyAdapter
        }
    }

    private fun setupFirestoreListener() {
        val currentUserUid = auth.currentUser?.uid
        if (currentUserUid == null) {
            Toast.makeText(this, "User not logged in.", Toast.LENGTH_SHORT).show()
            binding.noHistoryText.text = "User not logged in."
            binding.noHistoryText.visibility = View.VISIBLE
            return
        }

        binding.progressBar.visibility = View.VISIBLE

        db.collection("users").document(currentUserUid).collection("rideHistory")
            .orderBy("datetime", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, e ->
                if (isFinishing || isDestroyed) return@addSnapshotListener
                binding.progressBar.visibility = View.GONE

                if (e != null) {
                    Log.w("HistoryActivity", "Listen failed.", e)
                    binding.noHistoryText.text = "Failed to load history."
                    binding.noHistoryText.visibility = View.VISIBLE
                    binding.historyRecyclerView.visibility = View.GONE
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
                    Log.d("HistoryActivity", "Current data: null or empty")
                }
            }
    }

    private fun showClearHistoryConfirmation() {
        if (historyList.isEmpty()) {
            Toast.makeText(this, "No history to clear.", Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(this)
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
                binding.progressBar.visibility = View.GONE
                Toast.makeText(this, "History is already empty.", Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }

            val batch = db.batch()
            for (doc in snapshot.documents) {
                batch.delete(doc.reference)
            }

            batch.commit()
                .addOnSuccessListener {
                    binding.progressBar.visibility = View.GONE
                    Log.d("HistoryActivity", "All history successfully deleted.")
                    Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { e ->
                    binding.progressBar.visibility = View.GONE
                    Log.w("HistoryActivity", "Error deleting history.", e)
                    Toast.makeText(this, "Failed to clear history.", Toast.LENGTH_SHORT).show()
                }
        }.addOnFailureListener { e ->
            binding.progressBar.visibility = View.GONE
            Log.e("HistoryActivity", "Error fetching history for deletion", e)
            Toast.makeText(this, "Failed to access history.", Toast.LENGTH_SHORT).show()
        }
    }
}
