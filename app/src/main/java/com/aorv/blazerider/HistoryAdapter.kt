package com.aorv.blazerider

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.aorv.blazerider.databinding.HistoryItemBinding

class HistoryAdapter(private val historyList: List<RideHistory>) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = HistoryItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        val history = historyList[position]
        holder.bind(history)
    }

    override fun getItemCount(): Int = historyList.size

    inner class HistoryViewHolder(private val binding: HistoryItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(history: RideHistory) {
            binding.historyOrigin.text = "Origin: ${history.origin}"
            binding.historyDestination.text = "Destination: ${history.destination}"
            binding.historyDistance.text = "Distance: ${history.distance?.let { String.format("%.1f km", it) } ?: "Unknown"}"
            binding.historyDuration.text = "Duration: ${history.duration?.let { String.format("%.0f mins", it) } ?: "Unknown"}"
            binding.historyStatus.text = history.status
        }
    }
}
