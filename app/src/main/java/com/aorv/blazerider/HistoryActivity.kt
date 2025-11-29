package com.aorv.blazerider

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.aorv.blazerider.databinding.ActivityHistoryBinding

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

        setupRecyclerView()
        fetchRideHistory()
    }

    private fun setupRecyclerView() {
        historyAdapter = HistoryAdapter(historyList)
        binding.historyRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
            adapter = historyAdapter
        }
    }

    private fun fetchRideHistory() {
        val currentUserUid = auth.currentUser?.uid ?: return

        db.collection("users").document(currentUserUid).collection("rideHistory")
            .orderBy("datetime", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { documents ->
                if (documents.isEmpty) {
                    binding.noHistoryText.visibility = View.VISIBLE
                    binding.historyRecyclerView.visibility = View.GONE
                } else {
                    binding.noHistoryText.visibility = View.GONE
                    binding.historyRecyclerView.visibility = View.VISIBLE
                    historyList.clear()
                    for (document in documents) {
                        val history = document.toObject(RideHistory::class.java)
                        historyList.add(history)
                    }
                    historyAdapter.notifyDataSetChanged()
                }
            }
            .addOnFailureListener { exception ->
                // Handle error
            }
    }
}
